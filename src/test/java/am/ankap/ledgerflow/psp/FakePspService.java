package am.ankap.ledgerflow.psp;

import am.ankap.ledgerflow.shared.Money;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scriptable stand-in for the real provider.
 *
 * By default it behaves well: authorize succeeds, capture succeeds.
 * Queue results with willReturn(...) to script a specific scenario.
 */
public class FakePspService implements PspService {

    private final Deque<PspResult> scriptedAuthorize = new ArrayDeque<>();
    private final Deque<PspResult> scriptedCapture = new ArrayDeque<>();
    private final Deque<PspResult> scriptedLookup = new ArrayDeque<>();

    private final Map<String, PspResult> providerState = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public PspCall authorize(String reference, Money amount, String idempotencyKey) {
        calls.add("authorize:" + reference);
        PspResult result = scriptedAuthorize.isEmpty()
                ? new PspResult.Authorized("auth_" + UUID.randomUUID())
                : scriptedAuthorize.poll();
        rememberProviderTruth(reference, result);
        return new PspCall(result, 1, 10L);
    }

    @Override
    public PspCall capture(String pspReference, Money amount, String idempotencyKey) {
        calls.add("capture:" + pspReference);
        PspResult result = scriptedCapture.isEmpty()
                ? new PspResult.Captured(pspReference, amount.minorUnits())
                : scriptedCapture.poll();
        return new PspCall(result, 1, 10L);
    }

    @Override
    public PspCall lookupByReference(String reference) {
        calls.add("lookup:" + reference);
        PspResult result = scriptedLookup.isEmpty()
                ? providerState.getOrDefault(reference,
                        new PspResult.Failed("No authorization exists for " + reference))
                : scriptedLookup.poll();
        return new PspCall(result, 1, 5L);
    }

    public void willAuthorize(PspResult... results) {
        scriptedAuthorize.addAll(List.of(results));
    }

    public void willCapture(PspResult... results) {
        scriptedCapture.addAll(List.of(results));
    }

    public void willLookup(PspResult... results) {
        scriptedLookup.addAll(List.of(results));
    }

    /** Sets what the provider "really" did, regardless of what the caller was told. */
    public void providerTruth(String reference, PspResult truth) {
        providerState.put(reference, truth);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public void reset() {
        scriptedAuthorize.clear();
        scriptedCapture.clear();
        scriptedLookup.clear();
        providerState.clear();
        calls.clear();
    }

    private void rememberProviderTruth(String reference, PspResult result) {
        if (!(result instanceof PspResult.Unknown) && !(result instanceof PspResult.Failed)) {
            providerState.put(reference, result);
        }
    }
}