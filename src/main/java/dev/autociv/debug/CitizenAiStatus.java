package dev.autociv.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.npc.Villager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.lang.ref.WeakReference;

/** Latest server-side task state for each physical citizen, used by /civ gui and diagnostics. */
public final class CitizenAiStatus {

    public record Snapshot(String profession, String state, String target, long updatedAt) { }

    private static final Map<UUID, Snapshot> STATUSES = new HashMap<>();
    private static final Map<UUID, WeakReference<Villager>> VILLAGERS = new HashMap<>();
    private static final Map<UUID, CivilizationLabel> CIVILIZATION_LABELS = new HashMap<>();

    private record CivilizationLabel(String name, int color) { }

    private CitizenAiStatus() { }

    public static void report(UUID citizenId, String profession, String state, BlockPos target, long gameTime) {
        String targetText = target == null ? "-" : target.getX() + "," + target.getY() + "," + target.getZ();
        Snapshot previous = STATUSES.get(citizenId);
        if (previous != null && previous.profession().equals(profession)
                && previous.state().equals(state) && previous.target().equals(targetText)) {
            return;
        }
        STATUSES.put(citizenId, new Snapshot(profession, state, targetText, gameTime));
        updateNameplate(citizenId, profession, state);
    }

    public static void bind(UUID citizenId, Villager villager, String profession) {
        VILLAGERS.put(citizenId, new WeakReference<>(villager));
        updateNameplate(citizenId, profession, "ИЩЕТ РАБОТУ");
    }

    public static void bind(UUID citizenId, Villager villager, String profession,
                            String civilizationName, int civilizationColor) {
        VILLAGERS.put(citizenId, new WeakReference<>(villager));
        setCivilization(citizenId, civilizationName, civilizationColor);
        updateNameplate(citizenId, profession, "ИЩЕТ РАБОТУ");
    }

    public static void setCivilization(UUID citizenId, String civilizationName, int civilizationColor) {
        CivilizationLabel next = new CivilizationLabel(civilizationName == null ? "" : civilizationName,
                civilizationColor & 0xFFFFFF);
        if (next.equals(CIVILIZATION_LABELS.put(citizenId, next))) return;
        Snapshot status = STATUSES.get(citizenId);
        updateNameplate(citizenId, status == null ? "UNASSIGNED" : status.profession(),
                status == null ? "ИЩЕТ РАБОТУ" : status.state());
    }

    private static void updateNameplate(UUID citizenId, String profession, String state) {
        WeakReference<Villager> reference = VILLAGERS.get(citizenId);
        Villager villager = reference == null ? null : reference.get();
        if (villager == null || !villager.isAlive()) {
            VILLAGERS.remove(citizenId);
            return;
        }
        String role = professionName(profession);
        String activity = shortActivity(state);
        CivilizationLabel civilization = CIVILIZATION_LABELS.get(citizenId);
        Component label = Component.literal(role + " · " + activity).withStyle(ChatFormatting.GOLD);
        if (civilization != null && !civilization.name().isBlank()) {
            label = Component.literal(civilization.name() + " · ")
                    .withStyle(style -> style.withColor(TextColor.fromRgb(civilization.color())))
                    .append(label);
        }
        villager.setCustomName(label);
        villager.setCustomNameVisible(true);
    }

    private static String professionName(String profession) {
        return switch (profession.toUpperCase(java.util.Locale.ROOT)) {
            case "FARMER" -> "Фермер";
            case "LUMBERJACK" -> "Лесоруб";
            case "MINER" -> "Шахтёр";
            case "FISHERMAN" -> "Рыбак";
            case "HUNTER" -> "Охотник";
            case "SHEPHERD" -> "Пастух";
            case "BUILDER" -> "Зодчий";
            case "ENGINEER" -> "Механик";
            case "BLACKSMITH" -> "Кузнец";
            case "MERCHANT" -> "Купец";
            case "GUARD" -> "Страж";
            case "SOLDIER" -> "Воин";
            case "DOCTOR" -> "Целитель";
            case "RESEARCHER" -> "Летописец";
            case "TORCHER" -> "Факельщик";
            case "REST" -> "Житель";
            default -> "Поселенец";
        };
    }

    private static String shortActivity(String state) {
        String value = state.toUpperCase(java.util.Locale.ROOT);
        if (value.contains("ЖДЁТ РЕСУРСЫ")) return "ждёт припасы";
        if (value.contains("ЖДЁТ ОБЩИЙ СУНДУК")) return "ищет склад";
        if (value.contains("СДАЁТ ГРУЗ") || value.contains("НЕСЁТ ГРУЗ")) return "сдаёт добычу";
        if (value.contains("ДОБЫЧА В РЮКЗАКЕ")) return "добывает";
        if (value.contains("ЗАЩИЩАЕТ")) return "защищает город";
        if (value.contains("ВРАГ")) return "заметил врага";
        if (value.contains("СТРОИТ ДОМ")) return "строит дом";
        if (value.contains("СТРОИТ ")) return "строит город";
        if (value.contains("ФАКЕЛ")) return "освещает город";
        if (value.contains("ИЗГОТАВЛИВАЕТ") || value.contains("ГОТОВО:")) return "крафтит блоки";
        if (value.contains("МАСТЕРСКАЯ")) return "в мастерской";
        if (value.contains("СПИТ") || value.contains("КРОВАТ")) return "отдыхает";
        if (value.contains("РАБОЧИЙ ДЕНЬ ОКОНЧЕН")) return "отдыхает";
        if (value.contains("ПОСТУ")) return "на посту";
        if (value.contains("ИДЁТ")) return "в пути";
        return state.length() > 22 ? state.substring(0, 20) + "…" : state.toLowerCase(java.util.Locale.ROOT);
    }

    public static Snapshot get(UUID citizenId) {
        return STATUSES.get(citizenId);
    }

    public static void forget(UUID citizenId) {
        STATUSES.remove(citizenId);
        VILLAGERS.remove(citizenId);
        CIVILIZATION_LABELS.remove(citizenId);
    }
}
