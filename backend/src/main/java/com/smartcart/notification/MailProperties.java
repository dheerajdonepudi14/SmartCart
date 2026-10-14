package com.smartcart.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SMTP connection settings live under spring.mail; these control what we send and as whom. */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(boolean enabled, String from, String fromName) {}
