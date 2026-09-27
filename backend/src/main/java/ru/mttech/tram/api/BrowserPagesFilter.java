package ru.mttech.tram.api;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Человекочитаемые страницы для браузера на тех же адресах, что и машинные ответы.
 *
 * Браузер при переходе по ссылке шлёт {@code Accept: text/html} — ему отдаётся HTML-страница
 * (оглавление API с кнопкой «Выполнить», статус сервиса). Программы (curl, фронтенд, healthcheck,
 * скрипты в perf/) HTML не просят и по-прежнему получают JSON: контракт API не меняется.
 */
@Component
public class BrowserPagesFilter implements WebFilter {

    private static final Map<String, String> PAGES = Map.of(
            "/", "/docs.html",
            "/api", "/docs.html",
            "/api/", "/docs.html",
            "/actuator/health", "/status.html");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var req = exchange.getRequest();
        String page = PAGES.get(req.getPath().value());
        if (page != null) {
            // Один адрес — два представления: без Vary браузер отдал бы закэшированный HTML на запрос JSON.
            exchange.getResponse().getHeaders().add(HttpHeaders.VARY, HttpHeaders.ACCEPT);
            exchange.getResponse().getHeaders().setCacheControl("no-store");
        }
        if (page != null && req.getMethod() == HttpMethod.GET && wantsHtml(req.getHeaders())) {
            return chain.filter(exchange.mutate().request(r -> r.path(page)).build());
        }
        return chain.filter(exchange);
    }

    private static boolean wantsHtml(HttpHeaders h) {
        return h.getAccept().stream().anyMatch(t -> t.isCompatibleWith(MediaType.TEXT_HTML) && !t.isWildcardType());
    }
}
