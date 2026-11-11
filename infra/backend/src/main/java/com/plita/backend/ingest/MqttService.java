package com.plita.backend.ingest;

import com.plita.backend.config.AppSettings;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * MQTT-клиент бэкенда: подписка на телеметрию и публикация команд реле.
 *
 * <p>Сообщения из колбэка Paho сразу передаются в отдельный поток: внутри колбэка нельзя
 * блокироваться (БД, публикация с QoS 1), иначе Paho может зависнуть.
 */
@Service
public class MqttService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MqttService.class);
    public static final String[] SUBSCRIPTIONS = {"device/+/telemetry", "relay/+/power"};

    public static class MqttUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MqttUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AppSettings settings;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-dispatch");
        t.setDaemon(true);
        return t;
    });
    private volatile MqttClient client;
    private volatile boolean stopping;
    private Thread connector;

    public MqttService(AppSettings settings) {
        this.settings = settings;
    }

    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    /** Запуск: подключение в фоне с повторами; handler вызывается последовательно в одном потоке. */
    public synchronized void start(BiConsumer<String, byte[]> handler) {
        if (connector != null) {
            return;
        }
        String uri = "tcp://" + settings.mqttHost() + ":" + settings.mqttPort();
        try {
            client = new MqttClient(uri, settings.mqttClientId(), new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException("не удалось создать MQTT-клиент: " + e.getMessage(), e);
        }
        client.setTimeToWait(5000);
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                dispatcher.execute(() -> subscribe(reconnect));
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT: соединение потеряно ({}), переподключаюсь", cause == null ? "?" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                byte[] payload = message.getPayload();
                dispatcher.execute(() -> {
                    try {
                        handler.accept(topic, payload);
                    } catch (Exception e) { // одно битое сообщение не должно ронять подписку
                        log.error("Ошибка обработки сообщения {}", topic, e);
                    }
                });
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // не нужно
            }
        });

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setKeepAliveInterval(30);
        opts.setConnectionTimeout(10);
        opts.setMaxReconnectDelay(30_000);
        if (!settings.mqttUser().isEmpty()) {
            opts.setUserName(settings.mqttUser());
        }
        if (!settings.mqttPassword().isEmpty()) {
            opts.setPassword(settings.mqttPassword().toCharArray());
        }

        // Первое подключение делаем сами: автопереподключение Paho работает только после успешного connect()
        connector = new Thread(() -> {
            long backoff = 1000;
            while (!stopping) {
                try {
                    client.connect(opts);
                    return;
                } catch (MqttException e) {
                    log.warn("MQTT {} недоступен ({}), повтор через {} с", uri, e.getMessage(), backoff / 1000);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
        }, "mqtt-connect");
        connector.setDaemon(true);
        connector.start();
    }

    private void subscribe(boolean reconnect) {
        try {
            client.subscribe(SUBSCRIPTIONS, new int[]{0, 0});
            log.info("MQTT {} к {}:{}, подписки: {}", reconnect ? "переподключён" : "подключён",
                    settings.mqttHost(), settings.mqttPort(), String.join(", ", SUBSCRIPTIONS));
        } catch (MqttException e) {
            log.error("MQTT: не удалось подписаться: {}", e.getMessage());
        }
    }

    public void publish(String topic, String payload, int qos) {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            throw new MqttUnavailableException("нет соединения с MQTT-брокером", null);
        }
        try {
            c.publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, false);
        } catch (MqttException e) {
            throw new MqttUnavailableException("публикация в " + topic + " не удалась: " + e.getMessage(), e);
        }
    }

    public void sendRelayCommand(String relayId, int command) {
        if (command != 0 && command != 1) {
            throw new IllegalArgumentException("команда реле: 0 или 1");
        }
        publish("relay/" + relayId + "/command", Integer.toString(command), 1);
    }

    @Override
    public void destroy() {
        stopping = true;
        dispatcher.shutdownNow();
        MqttClient c = client;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect(1000);
                }
                c.close();
            } catch (MqttException e) {
                log.debug("MQTT close: {}", e.getMessage());
            }
        }
    }
}
