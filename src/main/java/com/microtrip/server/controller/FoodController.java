package com.microtrip.server.controller;

import com.microtrip.server.dto.FoodItem;
import com.microtrip.server.dto.FoodShop;
import com.microtrip.server.service.StaticDataService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 美食接口（公开，无需鉴权）：/food/list、/food/detail/:id、/food/shops。
 * 与 Node 版路由、响应结构完全对齐。
 */
@RestController
@RequestMapping({"/food", "/api/v1/food"})
public class FoodController {

    private final StaticDataService staticDataService;

    public FoodController(StaticDataService staticDataService) {
        this.staticDataService = staticDataService;
    }

    /** GET /food/list?city=成都 → { list: [...] } */
    @GetMapping("/list")
    public Map<String, List<FoodItem>> list(@RequestParam(defaultValue = "") String city) {
        return Map.of("list", staticDataService.foods(city));
    }

    /** GET /food/detail/:id?city=成都 → { item: ... } */
    @GetMapping("/detail/{id}")
    public Map<String, FoodItem> detail(@PathVariable int id,
                                        @RequestParam(defaultValue = "") String city) {
        return Map.of("item", staticDataService.food(id, city));
    }

    /** GET /food/shops?city=成都 → { list: [...] }（详情页「去哪吃」） */
    @GetMapping("/shops")
    public Map<String, List<FoodShop>> shops(@RequestParam(defaultValue = "") String city) {
        return Map.of("list", staticDataService.shops(city));
    }
}
