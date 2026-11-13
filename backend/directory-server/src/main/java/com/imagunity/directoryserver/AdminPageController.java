package com.imagunity.directoryserver;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Point d'entrée de l'interface d'administration ({@code /admin/}). */
@Controller
class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    String index() {
        return "redirect:/admin/index.html";
    }
}
