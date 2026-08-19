package com.example.myGithubAction;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@SecurityScheme(
		name = "bearer-jwt",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT",
		description = "JWT token for API authentication"
)
public class MyGithubActionApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyGithubActionApplication.class, args);
	}

}
