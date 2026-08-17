package am.ankap.mockpsp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
class AuthorizationStore {

    private final Map<String, Authorization> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byReference = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();

    void save(Authorization authorization, String idempotencyKey) {
        byId.put(authorization.id(), authorization);
        byReference.put(authorization.getReference(), authorization.id());
        if (idempotencyKey != null) {
            byIdempotencyKey.put(idempotencyKey, authorization.id());
        }
    }

    void linkIdempotencyKey(String idempotencyKey, String authorizationId) {
        if (idempotencyKey != null) {
            byIdempotencyKey.put(idempotencyKey, authorizationId);
        }
    }

    Optional<Authorization> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    Optional<Authorization> findByReference(String reference) {
        return Optional.ofNullable(byReference.get(reference)).map(byId::get);
    }

    Optional<Authorization> findByIdempotencyKey(String key) {
        return key == null ? Optional.empty()
                : Optional.ofNullable(byIdempotencyKey.get(key)).map(byId::get);
    }

    List<Authorization> allCaptured() {
        return byId.values().stream()
                .filter(Authorization::isCaptured)
                .sorted((a, b) -> a.getReference().compareTo(b.getReference()))
                .toList();
    }
}