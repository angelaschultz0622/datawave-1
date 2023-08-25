package datawave.microservice.map.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@EnableConfigurationProperties({MapServiceProperties.class})
@Configuration
public class MapServiceConfiguration {}
