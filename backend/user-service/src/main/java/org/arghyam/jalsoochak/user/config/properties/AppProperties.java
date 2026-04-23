package org.arghyam.jalsoochak.user.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "app")
@Component
@Getter
@Setter
public class AppProperties {

    private boolean singleTenantMode = false;

    public boolean isMultiTenantMode() {
        return !singleTenantMode;
    }
}
