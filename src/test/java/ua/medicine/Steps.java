package ua.medicine;

import java.util.Map;
import java.util.function.Consumer;

/** Scripted UI steps for {@link SnapshotMain}. */
final class Steps {

    private static final Map<String, Consumer<App>> STEPS = Map.of();

    private Steps() {
    }

    static Consumer<App> get(String name) {
        return STEPS.getOrDefault(name, app -> {
        });
    }
}
