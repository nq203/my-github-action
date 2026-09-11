package com.example.myGithubAction.auth.controller;

import com.example.myGithubAction.auth.dto.CreateUserRequest;
import com.example.myGithubAction.auth.dto.LoginRequest;
import com.example.myGithubAction.auth.dto.LoginResponse;
import com.example.myGithubAction.auth.dto.RegisterRequest;
import com.example.myGithubAction.auth.dto.UserResponse;
import com.example.myGithubAction.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Register request for username: {}", request.getUsername());
        LoginResponse response = authService.register(request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "User registered successfully");
        result.put("data", response);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request for username: {}", request.getUsername());
        LoginResponse response = authService.login(request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Login successful");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@Valid @RequestBody CreateUserRequest request) {
        log.info("Create user request for username: {}", request.getUsername());
        UserResponse response = authService.createUser(request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "User created successfully");
        result.put("data", response);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> getUserById(@PathVariable Long userId) {
        log.info("Get user request for id: {}", userId);
        UserResponse response = authService.getUserById(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/users/username/{username}")
    public ResponseEntity<Map<String, Object>> getUserByUsername(@PathVariable String username) {
        log.info("Get user request for username: {}", username);
        UserResponse response = authService.getUserByUsername(username);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getAllUsers() {
        log.info("Get all users request");
        List<UserResponse> users = authService.getAllUsers();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Retrieved " + users.size() + " users");
        result.put("data", users);

        return ResponseEntity.ok(result);
    }

    @PutMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long userId,
            @RequestBody CreateUserRequest request) {
        log.info("Update user request for id: {}", userId);
        UserResponse response = authService.updateUser(userId, request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "User updated successfully");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long userId) {
        log.info("Delete user request for id: {}", userId);
        authService.deleteUser(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "User deleted successfully");

        return ResponseEntity.ok(result);
    }
}
