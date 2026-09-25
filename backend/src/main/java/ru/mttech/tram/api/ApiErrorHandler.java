package ru.mttech.tram.api;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Единый формат ошибок — RFC 9457 Problem Details, сообщения на русском.
 * Стектрейсы наружу не отдаются.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException e) {
        return problem(e.status(), e.title(), e.getMessage());
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ProblemDetail input(ServerWebInputException e) {
        String param = e.getMethodParameter() == null ? "параметр" : e.getMethodParameter().getParameterName();
        return problem(HttpStatus.BAD_REQUEST, "Некорректный запрос",
                "Проверьте параметр «" + param + "». Даты — в формате ГГГГ-ММ-ДД, числа — цифрами.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException e) {
        HttpStatus st = HttpStatus.resolve(e.getStatusCode().value());
        if (st == HttpStatus.NOT_FOUND) return problem(st, "Не найдено", "Такого адреса в API нет. Список методов — в README.");
        return problem(st == null ? HttpStatus.BAD_REQUEST : st, "Некорректный запрос", "Запрос не может быть обработан: проверьте адрес и параметры.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail other(Exception e) {
        log.error("Необработанная ошибка", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка",
                "Сервис не смог обработать запрос. Повторите попытку; если ошибка повторится — сообщите администратору.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        p.setType(URI.create("about:blank"));
        return p;
    }
}
