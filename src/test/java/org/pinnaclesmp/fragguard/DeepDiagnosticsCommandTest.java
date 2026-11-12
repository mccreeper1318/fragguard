package org.pinnaclesmp.fragguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression coverage for Codex review on #180: duplicate deep scan ownership. */
class DeepDiagnosticsCommandTest {
    private static final UUID OPERATOR = UUID.fromString("aaaa1111-aaaa-1111-aaaa-111111111111");
    private static final UUID OTHER_OPERATOR = UUID.fromString("bbbb2222-bbbb-2222-bbbb-222222222222");

    @Test
    void duplicateDeepCommandPreservesOriginalCompletionAndDoesNotStartAgain() {
        Fixture f = fixture();
        CompletableFuture<DatabaseDeepDiagnostics.Report> pending = new CompletableFuture<>();
        when(f.deep.run(anyInt())).thenReturn(pending);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            runScheduledImmediately(f, bukkit);
            execute(f, f.operator, "deep");
            execute(f, f.operator, "deep");
            verify(f.deep, times(1)).run(anyInt());

            var report = mock(DatabaseDeepDiagnostics.Report.class);
            when(report.lines()).thenReturn(List.of("Deep validation: COMPLETE",
                    "Source snapshot verified"));
            pending.complete(report);
        }

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(f.operator, atLeastOnce()).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().map(
                PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(s -> s.contains("Source snapshot verified")),
                "rejected duplicate must not discard first scan's completion report");
    }

    @Test
    void duplicateDoesNotLoseDisconnectCancellationOfOriginalScan() {
        Fixture f = fixture();
        CompletableFuture<DatabaseDeepDiagnostics.Report> pending = new CompletableFuture<>();
        when(f.deep.run(anyInt())).thenReturn(pending);

        execute(f, f.operator, "deep");
        execute(f, f.operator, "deep");
        verify(f.deep, times(1)).run(anyInt());

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(f.operator);
        f.command.onPlayerQuit(quit);

        verify(f.deep, times(1)).cancel();
        verify(f.deep, times(1)).run(anyInt());
    }

    @Test
    void rejectingAnotherOperatorsScanDoesNotStealOwnershipOrCancelFirstScan() {
        Fixture f = fixture();
        CompletableFuture<DatabaseDeepDiagnostics.Report> pending = new CompletableFuture<>();
        when(f.deep.run(anyInt())).thenReturn(pending,
                CompletableFuture.failedFuture(new IllegalStateException(
                        "A deep diagnostic scan is already running")));
        Player other = player(OTHER_OPERATOR);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            runScheduledImmediately(f, bukkit);
            execute(f, f.operator, "deep");
            execute(f, other, "deep");

            PlayerQuitEvent otherQuit = mock(PlayerQuitEvent.class);
            when(otherQuit.getPlayer()).thenReturn(other);
            f.command.onPlayerQuit(otherQuit);
            verify(f.deep, never()).cancel();

            var report = mock(DatabaseDeepDiagnostics.Report.class);
            when(report.lines()).thenReturn(List.of("Original operator's report delivered"));
            pending.complete(report);
            verify(f.deep, times(2)).run(anyInt());
        }

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(f.operator, atLeastOnce()).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().map(
                PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(s -> s.contains("Original operator's report delivered")),
                "second operator's rejected scan must not affect the real report");
    }

    @Test
    void completedOldRequestDoesNotCancelAnotherOperatorsRunningScanAtQuit() {
        Fixture f = fixture();
        CompletableFuture<DatabaseDeepDiagnostics.Report> completed = CompletableFuture.completedFuture(
                mock(DatabaseDeepDiagnostics.Report.class));
        CompletableFuture<DatabaseDeepDiagnostics.Report> otherScan = new CompletableFuture<>();
        when(f.deep.run(anyInt())).thenReturn(completed, otherScan);
        Player other = player(OTHER_OPERATOR);
        // The first completion callback is intentionally queued: the
        // old owner may still have an entry while another scan begins.
        BukkitScheduler scheduler = f.scheduler;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(f.plugin), any(Runnable.class)))
                    .thenReturn(mock(BukkitTask.class));

            execute(f, f.operator, "deep");
            execute(f, other, "deep");
            PlayerQuitEvent oldQuit = mock(PlayerQuitEvent.class);
            when(oldQuit.getPlayer()).thenReturn(f.operator);
            f.command.onPlayerQuit(oldQuit);
            verify(f.deep, never()).cancel();
        }
    }

    private static Fixture fixture() {
        FragGuardPlugin plugin = mock(FragGuardPlugin.class);
        Database database = mock(Database.class);
        DatabaseDeepDiagnostics deep = mock(DatabaseDeepDiagnostics.class);
        Player operator = player(OPERATOR);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("diagnostics-deep-timeout-seconds", 3600);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDeepDiagnostics()).thenReturn(deep);
        return new Fixture(plugin, database, deep, operator, scheduler,
                new FragGuardCommand(plugin, database));
    }

    private static Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOp()).thenReturn(true);
        when(player.hasPermission("fragguard.admin")).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static void execute(Fixture f, Player player, String action) {
        f.command.onCommand(player, mock(Command.class), "fg",
                new String[]{"diagnostics", action});
    }

    private static void runScheduledImmediately(Fixture f, MockedStatic<Bukkit> bukkit) {
        bukkit.when(Bukkit::getScheduler).thenReturn(f.scheduler);
        when(f.scheduler.runTask(eq(f.plugin), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return mock(BukkitTask.class);
        });
    }

    private record Fixture(FragGuardPlugin plugin, Database database,
                           DatabaseDeepDiagnostics deep, Player operator,
                           BukkitScheduler scheduler, FragGuardCommand command) {
    }
}
