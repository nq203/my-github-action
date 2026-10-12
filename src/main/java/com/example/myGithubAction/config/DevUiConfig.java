package com.example.myGithubAction.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the dev tools under {@code /dev/**} from {@code classpath:/dev-ui/} (deliberately not
 * {@code static/}, so nothing is reachable when this is off). Only active with
 * {@code app.dev-ui.enabled=true}; {@link SecurityConfig} opens {@code /dev/**} under the same flag.
 * The page itself holds no data: everything it shows comes through the JWT-protected API.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.dev-ui.enabled", havingValue = "true")
public class DevUiConfig implements WebMvcConfigurer {

    private final Environment environment;

    public DevUiConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void warnInProd() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("app.dev-ui.enabled=true while the 'prod' profile is active: the unauthenticated dev "
                    + "pages under /dev/** are exposed. Set APP_DEV_UI_ENABLED=false.");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/dev/**").addResourceLocations("classpath:/dev-ui/");
    }
}
