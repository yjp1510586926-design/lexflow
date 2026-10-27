package com.lexflow.service;

import org.springframework.stereotype.Service;

@Service
public class SystemStatusService {

    public String status() {
        return "UP";
    }
}
