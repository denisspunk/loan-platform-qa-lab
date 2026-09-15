package lab.loans.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lab.loans.Bugs;
import lab.loans.domain.Loan;
import lab.loans.domain.LoanView;
import lab.loans.events.EventBus;
import lab.loans.events.PaymentReceived;
import lab.loans.service.PaymentProcessor;
import lab.loans.store.LoanRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST API on the JDK's built-in HTTP server, so the service needs no framework.
 *   POST /loans          {"deviceId","price","dailyRate"}   -> 201 loan
 *   GET  /loans?limit=N                                     -> 200 {"loans":[...]} newest first | 400
 *   GET  /loans/{id}                                        -> 200 loan | 404
 *   GET  /loans/{id}/payments                               -> 200 {"loanId","payments":[...]} | 404
 *   POST /payments       {"paymentId","loanId","amount"}    -> 202 accepted | 400 | 404
 *   GET  /health                                            -> 200 {"status":"UP"}
 *   GET  /                                                  -> 200 the web UI (text/html)
 * Any other path gets a JSON 404.
 * A payment is only validated here; it is applied asynchronously by the payments.received consumer.
 */
public class HttpApi {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    private static final Pattern PAYMENTS_PATH = Pattern.compile("^/loans/([^/]+)/payments$");
    private static final Pattern LIMIT_PARAM = Pattern.compile("(?:^|&)limit=([^&]*)");

    private final LoanRepository loans;
    private final EventBus bus;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private HttpServer server;

    public HttpApi(LoanRepository loans, EventBus bus, Clock clock) {
        this.loans = loans;
        this.bus = bus;
        this.clock = clock;
    }

    /** Starts the server on 127.0.0.1; port 0 picks a free port. Returns the actual port. */
    public int start(int port) throws IOException {
        return start("127.0.0.1", port);
    }

    /** Starts the server on the given host, e.g. 0.0.0.0 inside a container. Returns the actual port. */
    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/loans", exchange -> handle(exchange, this::routeLoans));
        server.createContext("/payments", exchange -> handle(exchange, this::routePayments));
        server.createContext("/health", exchange -> handle(exchange, this::routeHealth));
        server.createContext("/", exchange -> handle(exchange, this::routeWeb));
        server.setExecutor(executor);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private void handle(HttpExchange exchange, Route route) {
        try {
            route.handle(exchange);
        } catch (BadRequest e) {
            sendQuietly(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            sendQuietly(exchange, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void routeLoans(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (method.equals("POST") && path.equals("/loans")) {
            CreateLoanRequest request = read(exchange, CreateLoanRequest.class);
            if (request.deviceId() == null || request.deviceId().isBlank()) {
                throw new BadRequest("deviceId is required");
            }
            if (request.price() <= 0) {
                throw new BadRequest("price must be positive");
            }
            if (request.dailyRate() <= 0) {
                throw new BadRequest("dailyRate must be positive");
            }
            Loan loan = loans.save(new Loan(newId("LN"), request.deviceId(), request.price(), request.dailyRate()));
            send(exchange, 201, loan.view(clock.instant()));
        } else if (method.equals("GET") && path.equals("/loans")) {
            Instant now = clock.instant();
            List<LoanView> recent = loans.findRecent(limit(exchange)).stream().map(loan -> loan.view(now)).toList();
            send(exchange, 200, Map.of("loans", recent));
        } else if (method.equals("GET") && PAYMENTS_PATH.matcher(path).matches()) {
            Matcher matcher = PAYMENTS_PATH.matcher(path);
            matcher.matches();
            String loanId = matcher.group(1);
            if (loans.findById(loanId).isEmpty()) {
                send(exchange, 404, error("loan " + loanId + " not found"));
                return;
            }
            List<PaymentView> payments = loans.findPayments(loanId).stream().map(PaymentView::of).toList();
            send(exchange, 200, new PaymentHistory(loanId, payments));
        } else if (method.equals("GET") && path.startsWith("/loans/")) {
            String loanId = path.substring("/loans/".length());
            Optional<Loan> loan = loans.findById(loanId);
            if (loan.isPresent()) {
                send(exchange, 200, loan.get().view(clock.instant()));
            } else {
                send(exchange, 404, error("loan " + loanId + " not found"));
            }
        } else {
            send(exchange, 404, error("no route for " + method + " " + path));
        }
    }

    /** GET /loans/{id}/payments. A record keeps loanId before payments in the JSON. */
    private record PaymentHistory(String loanId, List<PaymentView> payments) {
    }

    private static int limit(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        Matcher matcher = LIMIT_PARAM.matcher(query == null ? "" : query);
        if (!matcher.find()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(matcher.group(1));
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // answered below like an out-of-range number
        }
        throw new BadRequest("limit must be a number from 1 to " + MAX_LIMIT);
    }

    /** The web UI at the root; every other path outside the API gets the same JSON 404 as the API itself. */
    private void routeWeb(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            sendPage(exchange, WebPage.INDEX);
            return;
        }
        send(exchange, 404, error("no route for " + method + " " + path));
    }

    /** The page is read from the classpath once, on first use. */
    private static final class WebPage {
        static final byte[] INDEX = read("/web/index.html");

        private static byte[] read(String resource) {
            try (InputStream page = HttpApi.class.getResourceAsStream(resource)) {
                if (page == null) {
                    throw new IllegalStateException(resource + " is missing from the classpath");
                }
                return page.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + resource, e);
            }
        }
    }

    private void sendPage(HttpExchange exchange, byte[] page) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, page.length);
        exchange.getResponseBody().write(page);
    }

    /** Liveness for the hosting platform and smoke tests: no dependencies are checked yet. */
    private void routeHealth(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (!method.equals("GET") || !path.equals("/health")) {
            send(exchange, 404, error("no route for " + method + " " + path));
            return;
        }
        send(exchange, 200, Map.of("status", "UP"));
    }

    private void routePayments(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (!method.equals("POST") || !path.equals("/payments")) {
            send(exchange, 404, error("no route for " + method + " " + path));
            return;
        }

        PaymentRequest request = read(exchange, PaymentRequest.class);
        if (request.paymentId() == null || request.paymentId().isBlank()) {
            throw new BadRequest("paymentId is required");
        }
        if (request.loanId() == null || request.loanId().isBlank()) {
            throw new BadRequest("loanId is required");
        }
        boolean amountAccepted = request.amount() > 0
                || (Bugs.ZERO_AMOUNT_ACCEPTED.isOn() && request.amount() == 0);
        if (!amountAccepted) {
            throw new BadRequest("amount must be positive");
        }
        if (loans.findById(request.loanId()).isEmpty()) {
            send(exchange, 404, error("loan " + request.loanId() + " not found"));
            return;
        }

        PaymentReceived event = new PaymentReceived(
                request.paymentId(), request.loanId(), request.amount(), clock.instant());
        bus.publish(PaymentProcessor.TOPIC, request.loanId(), event);
        send(exchange, 202, Map.of("paymentId", request.paymentId(), "status", "ACCEPTED"));
    }

    private <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            T value = json.readValue(body, type);
            if (value == null) {
                throw new BadRequest("request body is required");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new BadRequest("invalid JSON: " + e.getOriginalMessage());
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void sendQuietly(HttpExchange exchange, int status, Object body) {
        try {
            send(exchange, status, body);
        } catch (IOException ignored) {
            // headers were already sent; the client sees a broken response
        }
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
