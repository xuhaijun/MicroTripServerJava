package com.microtrip.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.common.BizException;
import com.microtrip.server.dto.*;
import com.microtrip.server.util.Haversine;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 静态数据服务：加载 data/*.json（美食/景点/门店），提供 {city} 占位符注入、
 * 按 id 查询、周边推荐。
 *
 * <p>数据集由服务端持有，与 App 端内置 mock 解耦；city 参数用于把
 * 「{city}历史博物馆」这类模板渲染为真实城市名。</p>
 *
 * <p><b>性能：按城市缓存「已注入」结果</b>。原始 JSON 只在启动时解析一次，
 * 但「{city} 注入」此前是<b>每次请求</b>都整表 map 一遍（每个元素都要做多次
 * {@code String.replace} 分配新字符串）。而静态数据本身不会变、城市取值也有限，
 * 因此按城市缓存注入结果是纯收益：命中后只做一次 Map 查表。</p>
 */
@Service
public class StaticDataService {

    /** 城市缓存上限：本地生活类应用城市量级有限，超过则整体清空（简单、无锁竞争、无内存泄漏） */
    private static final int CACHE_MAX = 64;

    private final ObjectMapper objectMapper;

    /** 原始模板（未注入 {city}），启动时加载一次 */
    private List<FoodItem> foods = List.of();
    private List<SceneryItem> sceneries = List.of();
    private List<FoodShop> shops = List.of();

    /** 按城市缓存的注入结果 */
    private final Map<String, List<FoodItem>> foodCache = new ConcurrentHashMap<>();
    private final Map<String, List<SceneryItem>> sceneryCache = new ConcurrentHashMap<>();
    private final Map<String, List<FoodShop>> shopCache = new ConcurrentHashMap<>();

    public StaticDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        this.foods = readList("data/food.json", new TypeReference<>() {});
        this.sceneries = readList("data/scenery.json", new TypeReference<>() {});
        this.shops = readList("data/shops.json", new TypeReference<>() {});
    }

    // ---------------- 美食 ----------------

    public List<FoodItem> foods(String city) {
        return cached(foodCache, city,
                () -> foods.stream().map(f -> injectFood(f, city)).toList());
    }

    public FoodItem food(int id, String city) {
        return foods(city).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> BizException.notFound("美食不存在"));
    }

    public List<FoodShop> shops(String city) {
        return cached(shopCache, city,
                () -> shops.stream().map(s -> injectShop(s, city)).toList());
    }

    // ---------------- 景点 ----------------

    public List<SceneryItem> sceneries(String city) {
        return cached(sceneryCache, city,
                () -> sceneries.stream().map(s -> injectScenery(s, city)).toList());
    }

    public SceneryItem scenery(int id, String city) {
        return sceneries(city).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> BizException.notFound("景点不存在"));
    }

    /**
     * 周边推荐：排除自身，按真实球面距离升序取前 4。
     *
     * <p>修正：原实现先把距离格式化成「3.2km」文案，再 {@code parse} 回数值来排序 ——
     * 一次无谓的字符串往返，而且 {@code formatDistance} 只保留 1 位小数，
     * 相近景点会被压成同一个值，排序结果不稳定。现直接用 km 数值排序，
     * 只在最后一步才格式化给客户端。</p>
     */
    public List<NearbyScenery> nearby(int id, String city) {
        List<SceneryItem> injected = sceneries(city);
        SceneryItem current = injected.stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> BizException.notFound("景点不存在"));

        List<SceneryDistance> sorted = new ArrayList<>(injected.size());
        for (SceneryItem s : injected) {
            if (s.id() == id) continue;
            double km = Haversine.distance(current.latitude(), current.longitude(),
                    s.latitude(), s.longitude());
            sorted.add(new SceneryDistance(km, s));
        }
        sorted.sort(Comparator.comparingDouble(SceneryDistance::km));

        return sorted.stream()
                .limit(4)
                .map(sd -> new NearbyScenery(sd.item().id(), sd.item().name(), sd.item().image(),
                        sd.item().rating(), Haversine.formatDistance(sd.km())))
                .toList();
    }

    /** 排序中间体：已注入城市名的景点 + 到当前景点的球面距离（km） */
    private record SceneryDistance(double km, SceneryItem item) {
    }

    // ---------------- {city} 注入 ----------------

    private FoodItem injectFood(FoodItem f, String city) {
        return new FoodItem(f.id(), rep(f.name(), city), f.image(), f.rating(),
                f.price(), f.tag(), f.tags(), rep(f.desc(), city), rep(f.tips(), city));
    }

    private SceneryItem injectScenery(SceneryItem s, String city) {
        return new SceneryItem(s.id(), rep(s.name(), city), s.image(), s.rating(),
                s.ticket(), s.duration(), s.openTime(), s.tag(), s.tags(),
                rep(s.desc(), city), rep(s.address(), city), s.latitude(), s.longitude());
    }

    private FoodShop injectShop(FoodShop s, String city) {
        return new FoodShop(s.id(), rep(s.name(), city), s.rating(),
                s.distance(), s.avgPrice(), rep(s.address(), city), s.latitude(), s.longitude());
    }

    /** 把字符串中的 {city} 占位符替换为真实城市名（city 为空则不替换） */
    private String rep(String s, String city) {
        if (s == null || city == null || city.isBlank()) return s;
        return s.replace("{city}", city);
    }

    // ---------------- 缓存 ----------------

    /**
     * 按城市取「已注入」结果，未命中则用 loader 计算并缓存。
     *
     * <p>用 {@link ConcurrentHashMap} 而非 synchronized：静态数据是只读的，
     * 并发写同一 key 只会重复计算一次（无害），换来的是完全无锁的读路径。</p>
     *
     * <p>缓存键做 trim 归一化，避免 {@code "成都"} 与 {@code "成都 "} 存两份；
     * 键本身可能是 null/空串（未传 city），统一收敛为 {@code ""}。</p>
     */
    private <T> List<T> cached(Map<String, List<T>> cache, String city, Supplier<List<T>> loader) {
        String key = city == null ? "" : city.trim();
        List<T> hit = cache.get(key);
        if (hit != null) return hit;
        List<T> value = loader.get();
        if (cache.size() >= CACHE_MAX) {
            cache.clear(); // 极端城市量级下整体重置，策略简单且不会无限增长
        }
        cache.putIfAbsent(key, value);
        return cache.get(key);
    }

    // ---------------- 加载 ----------------

    private <T> List<T> readList(String path, TypeReference<List<T>> typeRef) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            List<T> list = objectMapper.readValue(is, typeRef);
            return list == null ? List.of() : list;
        } catch (IOException e) {
            return List.of();
        }
    }
}
