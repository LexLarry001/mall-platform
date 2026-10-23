package com.mall.gateway;

import com.mall.common.result.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 骨架连通性探针。
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public R<String> ping() {
        return R.ok("pong");
    }
}
