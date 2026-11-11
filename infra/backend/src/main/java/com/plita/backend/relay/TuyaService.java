package com.plita.backend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.config.AppSettings;
import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.RelayReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Силовой автомат Tongou TO-Q-SY2-JWT на заводской прошивке Tuya.
 *
 * <p>Автомат шлёт ток, напряжение и статус только в Tuya Cloud, к нашему брокеру он не подключается.
 * Бэкенд сам забирает эти данные по Tuya OpenAPI: раз в TUYA_POLL_INTERVAL_S опрашивает каждый автомат,
 * привязанный к Дому, и отдаёт отсчёт в Engine так же, как пакет relay/{id}/power от эмулятора.
 * Команды включения/выключения тоже идут через Tuya Cloud, после команды автомат опрашивается ещё раз.
 *
 * <p>Если TUYA_ACCESS_ID / TUYA_ACCESS_SECRET не заданы, сервис выключен: работает только эмулятор через MQTT.
 */
@Service
public class TuyaService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TuyaService.class);
    private static final long CONFIRM_POLL_DELAY_S = 3;

    private final AppSettings.Tuya cfg;
    private final TuyaClient client;
    private final TuyaStatusMapper statusMapper;
    private final ScheduledExecutorService scheduler;

    private Supplier<List<String>> relayIds = List::of;
    private BiConsumer<String, RelayReading> sink = (id, r) -> { };

    /** Последняя ошибка по устройству: пишем в лог только при её смене, чтобы не засорять его каждый опрос. */
    private final Map<String, String> lastError = new ConcurrentHashMap<>();
    private final Set<String> codesLogged = ConcurrentHashMap.newKeySet();

    public TuyaService(AppSettings settings, ObjectMapper mapper) {
        this.cfg = settings.tuya();
        this.statusMapper = new TuyaStatusMapper(new TuyaStatusMapper.Codes(cfg.switchCode(), cfg.currentCode(),
                cfg.currentScale(), cfg.voltageCode(), cfg.voltageScale()));
        if (cfg.enabled()) {
            this.client = new TuyaClient(cfg.endpoint(), cfg.accessId(), cfg.accessSecret(), mapper);
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tuya-poll");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.client = null;
            this.scheduler = null;
        }
    }

    public boolean enabled() {
        return client != null;
    }

    /** Таймаут сторожа связи для автомата Tuya: три пропущенных опроса и запас. */
    public double relayTimeoutS() {
        return cfg.relayTimeoutS();
    }

    /**
     * Запуск опроса. relayIds - автоматы Tuya, привязанные к Домам; sink - куда отдавать отсчёты (Engine).
     */
    public synchronized void start(Supplier<List<String>> relayIds, BiConsumer<String, RelayReading> sink) {
        if (!enabled()) {
            log.info("Tuya выключена (нет TUYA_ACCESS_ID / TUYA_ACCESS_SECRET): реле работает только через MQTT-эмулятор");
            return;
        }
        this.relayIds = relayIds;
        this.sink = sink;
        long period = Math.round(cfg.pollIntervalS());
        scheduler.scheduleWithFixedDelay(this::pollAll, 5, period, TimeUnit.SECONDS);
        log.info("Tuya: {}, опрос автоматов раз в {} с, коды DP: выключатель {}, ток {} x{}, напряжение {} x{}",
                cfg.endpoint(), period, cfg.switchCode(), cfg.currentCode(), cfg.currentScale(),
                cfg.voltageCode(), cfg.voltageScale());
    }

    private void pollAll() {
        List<String> ids;
        try {
            ids = relayIds.get();
        } catch (RuntimeException e) {
            log.error("Tuya: не удалось получить список автоматов", e);
            return;
        }
        for (String id : ids) {
            poll(id);
        }
    }

    /** Один опрос автомата; ошибки не выходят наружу, чтобы не остановить расписание. */
    void poll(String deviceId) {
        try {
            TuyaClient.DeviceState st = client.device(deviceId);
            if (codesLogged.add(deviceId)) {
                log.info("Tuya {}: online={}, статус {}", deviceId, st.online(), st.status());
            }
            if (!st.online()) {
                // данных нет - через relayTimeoutS сторож связи пришлёт relay_offline
                problem(deviceId, "автомат офлайн в Tuya Cloud");
                return;
            }
            RelayReading r = statusMapper.map(st.status());
            if (lastError.remove(deviceId) != null) {
                log.info("Tuya {}: данные снова приходят", deviceId);
            }
            sink.accept(deviceId, r);
        } catch (TuyaException | PacketException e) {
            problem(deviceId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tuya {}: ошибка опроса", deviceId, e);
        }
    }

    private void problem(String deviceId, String message) {
        String prev = lastError.put(deviceId, message);
        if (!message.equals(prev)) {
            log.warn("Tuya {}: {}", deviceId, message);
        }
    }

    /** Команда автомату: on=false - выключить плиту. Бросает TuyaException, если команда не ушла. */
    public void sendSwitch(String deviceId, boolean on) {
        if (!enabled()) {
            throw new TuyaException(0, "Tuya не настроена (TUYA_ACCESS_ID / TUYA_ACCESS_SECRET)");
        }
        client.sendCommand(deviceId, cfg.switchCode(), on);
        // подтверждаем новое состояние, не дожидаясь планового опроса
        scheduler.schedule(() -> poll(deviceId), CONFIRM_POLL_DELAY_S, TimeUnit.SECONDS);
    }

    /** Проверка при сопряжении: автомат виден в облачном проекте. Бросает TuyaException, если нет. */
    public TuyaClient.DeviceState verify(String deviceId) {
        if (!enabled()) {
            throw new TuyaException(0, "Tuya не настроена (TUYA_ACCESS_ID / TUYA_ACCESS_SECRET)");
        }
        return client.device(deviceId);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
