package com.production.incidents.web;

import com.production.incidents.service.ErrorGroupService;
import com.production.incidents.web.dto.Dtos.ErrorGroupDetail;
import com.production.incidents.web.dto.Dtos.ErrorGroupDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/error-groups")
public class ErrorGroupController {

    private final ErrorGroupService service;

    public ErrorGroupController(ErrorGroupService service) {
        this.service = service;
    }

    @GetMapping
    public List<ErrorGroupDto> list(@RequestParam(required = false) String service,
                                    @RequestParam(defaultValue = "false") boolean recurringOnly) {
        return this.service.list(service, recurringOnly);
    }

    @GetMapping("/{id}")
    public ErrorGroupDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }
}
