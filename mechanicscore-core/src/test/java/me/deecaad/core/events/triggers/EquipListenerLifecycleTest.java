package me.deecaad.core.events.triggers;

import com.cjcrafter.foliascheduler.EntitySchedulerImplementation;
import com.cjcrafter.foliascheduler.ServerImplementation;
import me.deecaad.core.MechanicsCore;
import me.deecaad.core.MechanicsPlugin;
import me.deecaad.core.events.EntityEquipmentEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EquipListenerLifecycleTest {

    enum MarkerKind { CANCELLED_DROP, INVENTORY_DROP }
    enum ReleaseMode { NEXT_TICK, RETIRED, REJECTED, THROWING }

    private record Scheduled(Runnable run, Runnable retired) {}

    private final EquipListener listener = EquipListener.SINGLETON;
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Listener> registrations = new ArrayList<>();
    private MechanicsCore plugin;
    private ServerImplementation scheduler;
    private EntitySchedulerImplementation entityScheduler;
    private PluginManager pluginManager;
    private Player player;
    private Map<UUID, Object> cancelled;
    private Map<UUID, Object> ignored;
    private MockedStatic<MechanicsCore> core;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<HandlerList> handlers;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        listener.clear();
        cancelled = markerMap("dropCancelledPlayers");
        ignored = markerMap("ignoreGiveDropPlayers");
        plugin = mock(MechanicsCore.class, RETURNS_DEEP_STUBS);
        scheduler = mock(ServerImplementation.class);
        entityScheduler = mock(EntitySchedulerImplementation.class);
        pluginManager = mock(PluginManager.class);
        player = player(UUID.randomUUID());
        when(plugin.getFoliaScheduler()).thenReturn(scheduler);
        when(scheduler.entity(any(Player.class))).thenReturn(entityScheduler);
        when(entityScheduler.execute(any(Runnable.class), any(Runnable.class), eq(1L)))
            .thenAnswer(invocation -> {
                scheduled.add(new Scheduled(invocation.getArgument(0), invocation.getArgument(1)));
                return true;
            });
        doAnswer(invocation -> {
            registrations.add(invocation.getArgument(0));
            return null;
        }).when(pluginManager).registerEvents(any(Listener.class), same(plugin));

        core = mockStatic(MechanicsCore.class);
        core.when(MechanicsCore::getInstance).thenReturn(plugin);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        handlers = mockStatic(HandlerList.class);
    }

    @AfterEach
    void tearDown() {
        listener.clear();
        if (handlers != null)
            handlers.close();
        if (bukkit != null)
            bukkit.close();
        if (core != null)
            core.close();
    }

    @Test
    void cancelledDropConsumesAnExistingIgnoreMarker() {
        mark(MarkerKind.INVENTORY_DROP, player);
        mark(MarkerKind.CANCELLED_DROP, player);

        assertTrue(ignored.isEmpty());
        assertEquals(List.of(player.getUniqueId()), List.copyOf(cancelled.keySet()));
        verify(pluginManager, never()).callEvent(any());

        // The cancelled synthetic drop must not suppress an unrelated later real drop.
        listener.onDrop(new PlayerDropItemEvent(player, mock(Item.class)));

        verify(pluginManager).callEvent(any(EntityEquipmentEvent.class));
    }

    @ParameterizedTest
    @EnumSource(MarkerKind.class)
    void markersExpireNextTickWithoutAnOldCallbackRemovingANewerMarker(MarkerKind kind) {
        mark(kind, player);
        Object first = markers(kind).get(player.getUniqueId());
        mark(kind, player);
        Object replacement = markers(kind).get(player.getUniqueId());
        assertNotSame(first, replacement);

        scheduled.get(0).run().run();
        assertSame(replacement, markers(kind).get(player.getUniqueId()));

        scheduled.get(1).run().run();
        assertTrue(markers(kind).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(MarkerKind.class)
    void entityRetirementRemovesItsMarker(MarkerKind kind) {
        mark(kind, player);

        scheduled.getFirst().retired().run();

        assertTrue(markers(kind).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schedulingFailureDoesNotLeaveEitherMarkerBehind(boolean throwsException) {
        IllegalStateException failure = new IllegalStateException("scheduler stopped");
        if (throwsException)
            when(entityScheduler.execute(any(), any(), eq(1L))).thenThrow(failure);
        else
            when(entityScheduler.execute(any(), any(), eq(1L))).thenReturn(false);

        for (MarkerKind kind : MarkerKind.values()) {
            if (throwsException)
                assertSame(failure, assertThrows(IllegalStateException.class, () -> mark(kind, player)));
            else
                mark(kind, player);
            assertTrue(markers(kind).isEmpty(), kind.name());
        }
    }

    @Test
    void quittingClearsBothMarkersForOnlyThatPlayersUuid() {
        Player other = player(UUID.randomUUID());
        markBoth(player);
        markBoth(other);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onQuit(event);

        assertEquals(List.of(other.getUniqueId()), List.copyOf(cancelled.keySet()));
        assertEquals(List.of(other.getUniqueId()), List.copyOf(ignored.keySet()));
    }

    @Test
    void clearRemovesAllMarkersAndAlreadyQueuedCallbacksRemainSafe() {
        markBoth(player);
        markBoth(player(UUID.randomUUID()));

        listener.clear();
        scheduled.forEach(task -> task.run().run());

        assertTrue(cancelled.isEmpty());
        assertTrue(ignored.isEmpty());
    }

    @Test
    void disablingThePluginClearsTheSingletonMarkers() throws ReflectiveOperationException {
        markBoth(player);
        Field field = MechanicsPlugin.class.getDeclaredField("foliaScheduler");
        field.setAccessible(true);
        field.set(plugin, scheduler);
        doCallRealMethod().when(plugin).onDisable();

        plugin.onDisable();

        assertTrue(cancelled.isEmpty());
        assertTrue(ignored.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(ReleaseMode.class)
    void giveListenerIsUnregisteredOnCompletionRetirementOrSchedulingFailure(ReleaseMode mode) {
        IllegalStateException failure = new IllegalStateException("scheduler stopped");
        if (mode == ReleaseMode.REJECTED)
            when(entityScheduler.execute(any(), any(), eq(1L))).thenReturn(false);
        else if (mode == ReleaseMode.THROWING)
            when(entityScheduler.execute(any(), any(), eq(1L))).thenThrow(failure);

        PlayerCommandPreprocessEvent command = command("/give player stone");
        if (mode == ReleaseMode.THROWING)
            assertSame(failure, assertThrows(IllegalStateException.class, () -> listener.onCommand(command)));
        else
            listener.onCommand(command);

        assertEquals(1, registrations.size());
        Listener temporary = registrations.getFirst();
        if (mode == ReleaseMode.NEXT_TICK || mode == ReleaseMode.RETIRED) {
            handlers.verify(() -> HandlerList.unregisterAll(temporary), never());
            if (mode == ReleaseMode.NEXT_TICK)
                scheduled.getFirst().run().run();
            else
                scheduled.getFirst().retired().run();
        }
        handlers.verify(() -> HandlerList.unregisterAll(temporary));
    }

    @Test
    void giveListenerMatchesPlayerUuidAndItsMarkerExpires() throws ReflectiveOperationException {
        listener.onCommand(command("/minecraft:give player stone"));
        Listener temporary = registrations.getFirst();
        Method onDrop = temporary.getClass().getDeclaredMethod("onDrop", PlayerDropItemEvent.class);
        onDrop.setAccessible(true);

        Player other = player(UUID.randomUUID());
        onDrop.invoke(temporary, new PlayerDropItemEvent(other, mock(Item.class)));
        assertTrue(ignored.isEmpty());

        Player sameUuid = player(player.getUniqueId());
        onDrop.invoke(temporary, new PlayerDropItemEvent(sameUuid, mock(Item.class)));
        assertEquals(List.of(player.getUniqueId()), List.copyOf(ignored.keySet()));
        assertEquals(2, scheduled.size());

        scheduled.get(1).run().run();
        assertTrue(ignored.isEmpty());
    }

    private PlayerCommandPreprocessEvent command(String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        return event;
    }
    private Player player(UUID uuid) {
        Player result = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(result.getUniqueId()).thenReturn(uuid);
        when(result.getInventory()).thenReturn(inventory);
        return result;
    }

    private void markBoth(Player target) {
        mark(MarkerKind.CANCELLED_DROP, target);
        mark(MarkerKind.INVENTORY_DROP, target);
    }

    private void mark(MarkerKind kind, Player target) {
        if (kind == MarkerKind.CANCELLED_DROP) {
            PlayerDropItemEvent event = new PlayerDropItemEvent(target, mock(Item.class));
            event.setCancelled(true);
            listener.onDrop(event);
        } else {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            ItemStack cursor = mock(ItemStack.class);
            when(cursor.getType()).thenReturn(Material.STONE);
            when(event.getSlot()).thenReturn(-999);
            when(event.getCursor()).thenReturn(cursor);
            when(event.getWhoClicked()).thenReturn(target);
            listener.onInventoryDrop(event);
        }
    }

    private Map<UUID, Object> markers(MarkerKind kind) {
        return kind == MarkerKind.CANCELLED_DROP ? cancelled : ignored;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> markerMap(String name) throws ReflectiveOperationException {
        Field field = EquipListener.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(listener);
    }
}
