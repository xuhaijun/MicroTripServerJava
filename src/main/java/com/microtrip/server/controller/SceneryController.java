package com.microtrip.server.controller;

import com.microtrip.server.dto.NearbyScenery;
import com.microtrip.server.dto.SceneryItem;
import com.microtrip.server.service.StaticDataService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 景点接口（公开，无需鉴权）：/scenery/list、/scenery/detail/:id、/scenery/nearby/:id。
 * 与 Node 版路由、响应结构完全对齐。
 */
@RestController
@RequestMapping({"/scenery", "/api/v1/scenery"})
public class SceneryController {

    private final StaticDataService staticDataService;

    public SceneryController(StaticDataService staticDataService) {
        this.staticDataService = staticDataService;
    }

    /** GET /scenery/list?city=成都 → { list: [...] } */
    @GetMapping("/list")
    public Map<String, List<SceneryItem>> list(@RequestParam(defaultValue = "") String city) {
        return Map.of("list", staticDataService.sceneries(city));
    }

    /** GET /scenery/detail/:id?city=成都 → { item: ... } */
    @GetMapping("/detail/{id}")
    public Map<String, SceneryItem> detail(@PathVariable int id,
                                           @RequestParam(defaultValue = "") String city) {
        return Map.of("item", staticDataService.scenery(id, city));
    }

    /** GET /scenery/nearby/:id?city=成都 → { list: [NearbyScenery] } */
    @GetMapping("/nearby/{id}")
    public Map<String, List<NearbyScenery>> nearby(@PathVariable int id,
                                                   @RequestParam(defaultValue = "") String city) {
        return Map.of("list", staticDataService.nearby(id, city));
    }
}
