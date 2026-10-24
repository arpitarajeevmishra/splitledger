package com.splitledger.payment.razorpay;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for api.razorpay.com, on an ephemeral port.
 *
 * <p>Shared by the integration tests because the one thing it has to get right is easy to
 * get wrong twice: **every order id must be unique for the lifetime of the database**.
 * {@code settlements.gateway_order_id} is UNIQUE, so a stub that numbers its orders from a
 * counter hands the second test the first test's id and the insert is refused. That
 * happened, in CI, after the same mistake had already been found and fixed in a local
 * script and in one of the two tests here -- which is the argument for there being exactly
 * one stub rather than one per test class.
 *
 * <p>A real gateway never reissues an order id either, so the random id is also the more
 * faithful stand-in.
 */
final class GatewayStub implements AutoCloseable {

    private final HttpServer server;
    private final List<String> orders = new CopyOnWriteArrayList<>();

    GatewayStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("could not start the gateway stub", ex);
        }
        server.createContext("/v1/orders", exchange -> {
            orders.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = ("{\"id\":\"" + newOrderId() + "\",\"status\":\"created\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** Razorpay's ids are opaque and never repeat; so are these. */
    private static String newOrderId() {
        return "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** The bodies of every order request, in arrival order. */
    List<String> ordersReceived() {
        return List.copyOf(orders);
    }

    void forgetOrdersReceived() {
        orders.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
