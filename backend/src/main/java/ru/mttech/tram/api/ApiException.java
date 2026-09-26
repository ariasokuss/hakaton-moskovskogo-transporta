package ru.mttech.tram.api;

import org.springframework.http.HttpStatus;

/** Ошибка с понятным пользователю сообщением. Превращается в RFC 9457 Problem Details. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }
}
