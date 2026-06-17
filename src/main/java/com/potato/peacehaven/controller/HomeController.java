package com.potato.peacehaven.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller // 注意：这里用 Controller，返回视图名称
public class HomeController {

    // @Autowired
    // private UserService userService; // 假设你有 Service

    @GetMapping("/")
    public String index(Model model) {
        // 1. 查询数据
        // User user = userService.getUserById(1L);
        
        // 2. 把数据放入 Model
        model.addAttribute("test", new Test());
        
        // 3. 返回模板名称 (对应 templates/index.html)
        return "index";
    }

    class Test {
        public String message = "Hello, Peace Haven!";
    }
}
