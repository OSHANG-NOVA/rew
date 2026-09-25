package com.rew.export;

import com.rew.RewConfig;
import com.rew.RewMod;
import com.rew.data.ConditionRef;
import com.rew.data.ContentRef;
import com.rew.data.RecipeSnapshot;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把快照导出成 KubeJS 脚本。
 *
 * <p>目标形态（GT 自带 KubeJS 集成注册的 schema，见 {@code GregTechKubeJSPlugin}）：
 * <pre>
 * ServerEvents.recipes(event =&gt; {
 *     event.recipes.gtceu.macerator('minecraft/crushed_iron')
 *         .itemInputs('minecraft:iron_ingot')
 *         .itemOutputs('2x minecraft:iron_nugget')
 *         .duration(200).EUt(30)
 * })
 * </pre>
 *
 * <p>导出是**尽力而为**：GT 支持的内容形态极多（ranged、NBT 断言、分数概率、各种条件），
 * 不可能全部映射成 KJS 调用。映射不了的部分会以注释形式原样写出 JSON，
 * 保证信息不丢，作者可以手工接续。
 */
public final class KubeJsExporter {

    private KubeJsExporter() {}

    public static final class Result {

        public final Path file;
        public final int exported;
        public final List<String> warnings;

        Result(Path file, int exported, List<String> warnings) {
            this.file = file;
            this.exported = exported;
            this.warnings = warnings;
        }
    }

    /** 导出给定配方集合。 */
    public static Result export(List<RecipeSnapshot> recipes, String fileName) {
        Path dir = FMLPaths.GAMEDIR.get().resolve(RewConfig.get().kubejsExportDir);
        Path file = dir.resolve(fileName);
        List<String> warnings = new ArrayList<>();
        int exported = 0;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("// 由 Recipe Editor Workshop 导出 —— 请勿手工与编辑器同时维护同一份文件。\n");
        sb.append("// 生成时间：").append(new java.util.Date()).append('\n');
        sb.append("// 条目数：").append(recipes.size()).append("\n\n");
        sb.append("ServerEvents.recipes(event => {\n");

        for (RecipeSnapshot r : recipes) {
            try {
                String block = toScript(r, warnings);
                if (block != null) {
                    sb.append(indent(block, "    ")).append('\n');
                    exported++;
                }
            } catch (Throwable t) {
                warnings.add("配方 " + r.id + " 导出失败：" + t);
            }
        }
        sb.append("})\n");

        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            RewMod.LOGGER.info("[{}] KubeJS 导出完成：{} 条 → {}", RewMod.MOD_ID, exported, file);
        } catch (Exception e) {
            warnings.add("写文件失败：" + e);
            RewMod.LOGGER.error("[{}] KubeJS 导出写文件失败", RewMod.MOD_ID, e);
        }
        return new Result(file, exported, warnings);
    }

    /** 单条配方 → 一段脚本；类型无法映射成 gtceu.<type> 时返回 null。 */
    private static String toScript(RecipeSnapshot r, List<String> warnings) {
        String type = kjsTypeOf(r.typeId);
        if (type == null) {
            warnings.add("配方 " + r.id + " 的类型 " + r.typeId + " 不在 gtceu 命名空间下，已跳过");
            return null;
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("event.recipes.gtceu.").append(type)
                .append('(').append(quote(r.id)).append(")\n");

        for (ContentRef c : r.itemInputs) {
            sb.append(".itemInputs(").append(itemArg(c)).append(")\n");
        }
        for (ContentRef c : r.fluidInputs) {
            sb.append(".inputFluids(").append(fluidArg(c)).append(")\n");
        }
        for (ContentRef c : r.itemOutputs) {
            if (c.isCertain()) {
                sb.append(".itemOutputs(").append(itemArg(c)).append(")\n");
            } else {
                sb.append(".chancedOutput(").append(itemArg(c))
                        .append(", ").append(fractionOf(c)).append(", 0)\n");
            }
        }
        for (ContentRef c : r.fluidOutputs) {
            if (c.isCertain()) {
                sb.append(".outputFluids(").append(fluidArg(c)).append(")\n");
            } else {
                sb.append(".chancedFluidOutput(").append(fluidArg(c))
                        .append(", ").append(fractionOf(c)).append(", 0)\n");
            }
        }
        if (r.inputEUt > 0L) {
            // KJS 的 EUt 接受数字，也接受 "30V@2A" 这类字符串；多安培时用字符串形式。
            if (r.inputAmperage > 1L) {
                sb.append(".EUt(").append(quote(r.inputEUt + "V@" + r.inputAmperage + "A")).append(")\n");
            } else {
                sb.append(".EUt(").append(r.inputEUt).append(")\n");
            }
        }
        sb.append(".duration(").append(r.duration).append(")\n");

        for (ConditionRef cond : r.conditions) {
            String call = conditionCall(cond);
            if (call != null) {
                sb.append(call).append('\n');
            } else {
                warnings.add("配方 " + r.id + " 的条件 " + cond.type + " 无法映射为 KJS 调用，已写成注释");
                sb.append("// TODO 条件无法自动映射：").append(cond.json).append('\n');
            }
        }
        sb.append(';');
        return sb.toString();
    }

    /** {@code gtceu:macerator} → {@code macerator}；非 gtceu 命名空间返回 null。 */
    private static String kjsTypeOf(String typeId) {
        if (typeId == null) return null;
        int colon = typeId.indexOf(':');
        if (colon < 0) return null;
        if (!"gtceu".equals(typeId.substring(0, colon))) return null;
        return typeId.substring(colon + 1);
    }

    private static String itemArg(ContentRef c) {
        String base = c.tag ? "#" + c.id : c.id;
        String s = c.amount > 1L ? c.amount + "x " + base : base;
        return quote(s);
    }

    private static String fluidArg(ContentRef c) {
        // KJS 流体写法："minecraft:water 1000"
        String base = c.tag ? "#" + c.id : c.id;
        return c.amount > 1L ? quote(base + " " + c.amount) : quote(base);
    }

    /** 概率 → 分数串，例如 "1/3"；GT 的 KJS schema 接受这种形式。 */
    private static String fractionOf(ContentRef c) {
        int max = c.maxChance <= 0 ? 10000 : c.maxChance;
        int chance = Math.max(0, Math.min(c.chance, max));
        return quote(chance + "/" + max);
    }

    /**
     * 条件 → KJS 调用。只覆盖 GT 在 KJS schema 里提供了便捷方法的那几种；
     * 其余返回 null，由调用方降级为注释。
     */
    private static String conditionCall(ConditionRef cond) {
        if (cond.json == null || cond.json.isBlank()) return null;
        String type = cond.type == null ? "" : cond.type;
        String path = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        return switch (path) {
            case "dimension" -> readResourceArg(cond, "dimension", ".dimension(");
            case "biome" -> readResourceArg(cond, "biome", ".biome(");
            case "cleanroom" -> readResourceArg(cond, "cleanroomType", ".cleanroom(");
            default -> null;
        };
    }

    /** 从条件 JSON 里抠出某个字符串字段，拼成 {@code .xxx('value')}。 */
    private static String readResourceArg(ConditionRef cond, String field, String callPrefix) {
        try {
            var obj = com.google.gson.JsonParser.parseString(cond.json).getAsJsonObject();
            if (!obj.has(field)) return null;
            String v = obj.get(field).getAsString();
            boolean reverse = cond.reverse || (obj.has("reverse") && obj.get("reverse").getAsBoolean());
            return callPrefix + quote(v) + (reverse ? ", true" : "") + ")";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String indent(String block, String prefix) {
        StringBuilder sb = new StringBuilder(block.length() + 64);
        for (String line : block.split("\n")) {
            if (!line.isEmpty()) sb.append(prefix);
            sb.append(line).append('\n');
        }
        // 去掉最后多余的换行，由调用方统一控制
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
        return sb.toString();
    }
}
