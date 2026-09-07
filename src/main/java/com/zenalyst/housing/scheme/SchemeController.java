package com.zenalyst.housing.scheme;

import com.zenalyst.housing.platform.error.ApiException;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schemes")
public class SchemeController {

    private final SchemeRepository schemes;

    public SchemeController(SchemeRepository schemes) {
        this.schemes = schemes;
    }

    @GetMapping
    public List<SchemeResponse> list() {
        return schemes.findAll(Sort.by("code")).stream()
                .map(SchemeResponse::from)
                .toList();
    }

    @GetMapping("/{code}")
    public SchemeResponse byCode(@PathVariable String code) {
        return schemes.findByCode(code)
                .map(SchemeResponse::from)
                .orElseThrow(() -> ApiException.notFound("scheme", code));
    }
}
