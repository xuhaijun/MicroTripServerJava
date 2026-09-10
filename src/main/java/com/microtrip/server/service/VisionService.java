package com.microtrip.server.service;

import com.microtrip.server.common.BizException;
import com.microtrip.server.dto.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 拍照识物服务：解析图片 → 调第三方识别 → 按标签对 scenery/food 打分匹配，
 * 返回推荐景点/美食（各取前 3）。
 *
 * <p>匹配规则与 Node 版完全一致：
 * 标签名出现在名称/标签/描述中得 1 分；云端分类含「食物」对美食加权、
 * 含「建筑/场景/地标」对景点加权；按总分降序取前 3，无命中则为空数组。</p>
 */
@Service
public class VisionService {

    private static final Pattern FOOD_CAT = Pattern.compile("食物|food|cuisine|dish|meal", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCENERY_CAT = Pattern.compile("建筑|场景|地标|风景|landmark|building|scenery|site|architecture", Pattern.CASE_INSENSITIVE);

    private final VisionProvider provider;
    private final StaticDataService staticDataService;

    public VisionService(VisionProvider provider, StaticDataService staticDataService) {
        this.provider = provider;
        this.staticDataService = staticDataService;
    }

    public VisionResult recognize(String imageBase64, String city) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw BizException.badRequest("缺少图片数据（imageBase64）");
        }
        String base64 = normalizeBase64(imageBase64);

        if (!provider.isConfigured()) {
            throw BizException.notConfigured(
                    "Vision API 未配置：服务端需设置 VISION_SECRET_ID / VISION_SECRET_KEY 后启用真实识图");
        }

        List<VisionLabel> labels;
        try {
            labels = provider.recognize(base64);
        } catch (BizException ex) {
            throw ex; // 502 等已带状态码，直接透传
        } catch (Exception ex) {
            throw BizException.upstreamError("调用第三方 Vision 失败：" + ex.getMessage());
        }

        List<String> labelNames = labels.stream().map(VisionLabel::name).toList();
        Map<String, Integer> boosts = categoryBoost(labels);

        List<SceneryItem> scenery = staticDataService.sceneries(city).stream()
                .map(it -> new AbstractMap.SimpleEntry<>(it, scoreScenery(it, labelNames, boosts.get("scenery"))))
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<SceneryItem, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        List<FoodItem> food = staticDataService.foods(city).stream()
                .map(it -> new AbstractMap.SimpleEntry<>(it, scoreFood(it, labelNames, boosts.get("food"))))
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<FoodItem, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        return new VisionResult(labels, new VisionResult.Matches(scenery, food));
    }

    // ---------------- 打分 ----------------

    private int scoreScenery(SceneryItem it, List<String> labelNames, int boost) {
        String hay = joinLower(it.name(), it.tag(), it.tags(), it.desc(), it.address());
        int score = 0;
        for (String l : labelNames) {
            if (l != null && hay.contains(l.toLowerCase())) score += 1;
        }
        return score + boost;
    }

    private int scoreFood(FoodItem it, List<String> labelNames, int boost) {
        String hay = joinLower(it.name(), it.tag(), it.tags(), it.desc(), it.tips());
        int score = 0;
        for (String l : labelNames) {
            if (l != null && hay.contains(l.toLowerCase())) score += 1;
        }
        return score + boost;
    }

    /** 兼容「单个字段 + 一个标签列表」的拼接：把列表展平成字符串再参与检索。 */
    private String joinLower(String first, String second, List<String> tags, String fourth, String fifth) {
        List<String> all = new ArrayList<>(8);
        all.add(first);
        all.add(second);
        if (tags != null) all.addAll(tags);
        all.add(fourth);
        all.add(fifth);
        return joinLower(all.toArray(new String[0]));
    }

    private String joinLower(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) sb.append(p).append(" ");
        }
        return sb.toString().toLowerCase();
    }

    private Map<String, Integer> categoryBoost(List<VisionLabel> labels) {
        String joined = labels.stream().map(VisionLabel::category).filter(Objects::nonNull)
                .reduce("", (a, b) -> a + "," + b);
        int food = FOOD_CAT.matcher(joined).find() ? 1 : 0;
        int scenery = SCENERY_CAT.matcher(joined).find() ? 1 : 0;
        Map<String, Integer> m = new HashMap<>();
        m.put("food", food);
        m.put("scenery", scenery);
        return m;
    }

    /** 去掉 data:image/...;base64, 前缀（若有） */
    private String normalizeBase64(String input) {
        if (input.startsWith("data:image/") && input.contains(";base64,")) {
            return input.substring(input.indexOf(";base64,") + 8);
        }
        return input;
    }
}
