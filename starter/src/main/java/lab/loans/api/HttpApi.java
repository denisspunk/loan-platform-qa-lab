package lab.loans.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lab.loans.Bugs;
import lab.loans.domain.Loan;
import lab.loans.events.EventBus;
import lab.loans.events.PaymentReceived;
import lab.loans.service.PaymentProcessor;
import lab.loans.store.LoanRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST API on the JDK's built-in HTTP server, so the lab needs no Spring.
 *   POST /loans          {"deviceId","price","dailyRate"}   -> 201 loan
 *   GET  /loans/{id}                                        -> 200 loan | 404
 *   POST /payments       {"paymentId","loanId","amount"}    -> 202 accepted | 400 | 404
 *   GET  /health                                            -> 200 {"status":"UP"}
 * A payment is only validated here; it is applied asynchronously by the payments.received consumer.
 */
public class HttpApi {

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
