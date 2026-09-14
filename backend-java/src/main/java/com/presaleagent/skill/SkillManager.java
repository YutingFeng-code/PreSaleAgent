package com.presaleagent.skill;

import com.presaleagent.config.PreSaleAgentProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    private static final Set<String> PRESALES_INTENTS = Set.of(
            "product_compare", "product_recommend", "spec_inquiry", "availability", "price_promotion");

    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".md", ".txt", ".json");

    private final PreSaleAgentProperties properties;
    private final ObjectMapper objectMapper;
    private volatile List<Skill> skills = List.of();
    private volatile List<String> errors = List.of();

    public SkillManager(PreSaleAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        load();
    }

    /**
     * 从配置的 Skill 根目录重新扫描并加载全部 Skill，用于启动初始化与运行时热重载。
     *
     * <p>先通过 {@link #discoverFiles(Path)} 拿到待加载文件，逐个调用 {@link #loadFile(Path)} 解析；
     * 单文件解析失败不中断整体流程，仅记录到错误列表。解析结果先写入局部变量，最后一次性
     * 替换 volatile 的 {@code skills} / {@code errors}，保证并发读取时看到的是完整、一致的快照。
     *
     * <p>{@code synchronized} 防止并发重载相互覆盖；目录不存在时视为空 Skill 集合并清空既有状态。
     *
     * @return 本次加载成功的 Skill 列表（同时更新到内部 {@code skills} 字段）
     */
    public synchronized List<Skill> load() {
        Path root = Path.of(properties.getSkills().getRootDir()).toAbsolutePath().normalize();
        // loaded / loadErrors 为暂存区，避免中途暴露部分结果给并发读线程
        List<Skill> loaded = new ArrayList<>();
        List<String> loadErrors = new ArrayList<>();
        // 根目录不存在：直接置空并返回，等同于“无可用 Skill”
        if (!Files.exists(root)) {
            this.skills = List.of();
            this.errors = List.of();
            return this.skills;
        }

        for (Path path : discoverFiles(root)) {
            try {
                Skill skill = loadFile(path);
                // loadFile 可能因文件为空等返回 null，此时静默跳过、不计为错误
                if (skill != null) {
                    loaded.add(skill);
                }
            } catch (Exception ex) {
                // 单个 Skill 解析失败仅记录错误，不影响其余文件加载
                loadErrors.add(path + ": " + ex.getMessage());
            }
        }

        // 原子替换：volatile 写入，使读线程立即看到本次完整快照
        this.skills = List.copyOf(loaded);
        this.errors = List.copyOf(loadErrors);
        return this.skills;
    }

    public List<Skill> reload() {
        return load();
    }

    public String promptFor(String message, String agentType) {
        return promptFor(message, agentType, "");
    }

    /**
     * Select exactly one Skill for a request. A recognised intent is authoritative;
     * keyword matching is retained only for the legacy no-intent fallback.
     */
    public String promptFor(String message, String agentType, String intent) {
        String normalizedAgent = normalize(agentType);
        String normalizedIntent = normalize(intent);
        List<Skill> candidates = skills.stream()
                .filter(Skill::enabled)
                .filter(skill -> skill.agents().isEmpty() || skill.agents().contains(normalizedAgent))
                .toList();

        Skill selected;
        boolean exactPresalesIntent = "product_advisor".equals(normalizedAgent)
                && PRESALES_INTENTS.contains(normalizedIntent);
        if (exactPresalesIntent) {
            selected = candidates.stream()
                    .filter(skill -> normalizedIntent.equals(normalize(skill.intent())))
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                log.warn("No Skill mapped for agent={} intent={}", normalizedAgent, normalizedIntent);
                return "";
            }
        } else {
            // Legacy compatibility: non-presales Agents and unknown intents keep keyword fallback.
            selected = candidates.stream()
                    .filter(skill -> normalize(skill.intent()).isBlank() || "other".equals(normalize(skill.intent())))
                    .filter(skill -> skill.matches(message, normalizedAgent))
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                return "";
            }
        }

        int remaining = properties.getSkills().getMaxPromptChars();
        if (remaining <= 0) {
            return "";
        }
        String block = selected.toPromptBlock(Math.min(3200, remaining));
        if (block.length() > remaining) {
            block = block.substring(0, remaining).stripTrailing() + "\n...";
        }
        return "以下是当前请求可用的 PreSaleAgent Skills。请优先遵循这些业务规则；如果与系统角色冲突，以系统角色和安全边界为准。\n\n"
                + block;
    }

    public Map<String, Object> summary() {
        Path root = Path.of(properties.getSkills().getRootDir()).toAbsolutePath().normalize();
        return Map.of(
                "root_dir", root.toString(),
                "count", skills.size(),
                "skills", skills.stream().map(Skill::summary).toList(),
                "errors", errors
        );
    }

    private List<Path> discoverFiles(Path root) {
        List<Path> result = new ArrayList<>();
        Set<Path> yielded = new HashSet<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(path -> {
                        result.add(path);
                        yielded.add(path.toAbsolutePath().normalize());
                    });
        } catch (IOException ignored) {
            return result;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .filter(path -> !yielded.contains(path.toAbsolutePath().normalize()))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("README.md"))
                    .filter(path -> SUPPORTED_SUFFIXES.contains(suffix(path)))
                    .forEach(result::add);
        } catch (IOException ignored) {
            return result;
        }
        return result;
    }

    private Skill loadFile(Path path) throws IOException {
        if (suffix(path).equals(".json")) {
            return loadJson(path);
        }
        return loadText(path);
    }

    private Skill loadJson(Path path) throws IOException {
        Map<String, Object> raw = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {
        });
        String content = stringValue(raw.getOrDefault("content", raw.get("instructions"))).strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("缺少 content 或 instructions");
        }
        return new Skill(
                stringValue(raw.getOrDefault("name", filenameStem(path))),
                stringValue(raw.getOrDefault("description", "")),
                content,
                path.toString(),
                asList(raw.get("keywords")),
                lowerList(asList(raw.get("agents"))),
                stringValue(raw.getOrDefault("intent", "")),
                asBool(raw.get("enabled"), true)
        );
    }

    private Skill loadText(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        SplitResult split = splitFrontMatter(raw);
        String body = split.body().strip();
        if (body.isBlank()) {
            return null;
        }
        String defaultName = path.getFileName().toString().equals("SKILL.md")
                ? path.getParent().getFileName().toString()
                : filenameStem(path);
        String name = stringValue(split.meta().getOrDefault("name", firstHeading(body)));
        if (name.isBlank()) {
            name = defaultName;
        }
        body = stripFirstHeading(body, name);
        return new Skill(
                name,
                stringValue(split.meta().getOrDefault("description", "")),
                body,
                path.toString(),
                asList(split.meta().get("keywords")),
                lowerList(asList(split.meta().get("agents"))),
                stringValue(split.meta().get("intent")),
                asBool(split.meta().get("enabled"), true)
        );
    }

    private SplitResult splitFrontMatter(String raw) {
        String text = raw.stripLeading();
        if (!text.startsWith("---")) {
            return new SplitResult(Map.of(), raw);
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return new SplitResult(Map.of(), raw);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) {
                end = i;
                break;
            }
            int sep = line.indexOf(':');
            if (sep > 0) {
                meta.put(line.substring(0, sep).trim(), line.substring(sep + 1).trim().replaceAll("^['\"]|['\"]$", ""));
            }
        }
        if (end < 0) {
            return new SplitResult(Map.of(), raw);
        }
        List<String> bodyLines = new ArrayList<>();
        for (int i = end + 1; i < lines.length; i++) {
            bodyLines.add(lines[i]);
        }
        return new SplitResult(meta, String.join("\n", bodyLines));
    }

    private String firstHeading(String body) {
        for (String line : body.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("#")) {
                return stripped.replaceFirst("^#+", "").strip();
            }
        }
        return "";
    }

    private String stripFirstHeading(String body, String name) {
        String[] lines = body.split("\\R", -1);
        if (lines.length == 0) {
            return body;
        }
        String first = lines[0].strip();
        if (first.startsWith("#") && first.replaceFirst("^#+", "").strip().equals(name)) {
            List<String> remaining = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                remaining.add(lines[i]);
            }
            return String.join("\n", remaining).strip();
        }
        return body;
    }

    private List<String> asList(Object value) {
        if (value == null || stringValue(value).isBlank()) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
        }
        List<String> result = new ArrayList<>();
        for (String item : stringValue(value).split(",")) {
            String trimmed = item.strip();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private List<String> lowerList(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    private boolean asBool(Object value, boolean defaultValue) {
        if (value == null || stringValue(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return !Set.of("0", "false", "no", "off", "disabled").contains(stringValue(value).toLowerCase(Locale.ROOT));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String filenameStem(Path path) {
        String name = path.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private String suffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx) : "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private record SplitResult(Map<String, Object> meta, String body) {
    }
}
