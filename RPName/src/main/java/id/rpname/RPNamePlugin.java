package id.rpname;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@SuppressWarnings("UnstableApiUsage")
public class RPNamePlugin extends JavaPlugin implements Listener {

    private static final Pattern VALID = Pattern.compile("^[\\p{L}'-]{2,16}$");

    private File dataFile;
    private YamlConfiguration data;
    /** Pemain yang belum mengisi nama (dikunci sampai selesai). */
    private final Set<UUID> pending = new HashSet<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "names.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ---------- Data ----------

    private String getSavedName(UUID id) {
        return data.getString("names." + id);
    }

    private void saveName(UUID id, String fullName) {
        data.set("names." + id, fullName);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Gagal menyimpan names.yml: " + e.getMessage());
        }
    }

    private void removeName(UUID id) {
        data.set("names." + id, null);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Gagal menyimpan names.yml: " + e.getMessage());
        }
    }

    private static String capitalize(String s) {
        s = s.trim();
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // ---------- Apply nama ----------

    private void applyName(Player p, String fullName) {
        Component c = Component.text(fullName);
        p.displayName(c);      // chat & pesan lain yang pakai displayName
        p.playerListName(c);   // tab list
    }

    // ---------- GUI ----------

    private void openDialog(Player p, String error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(
                Component.text("Masukkan nama karakter roleplay kamu.", NamedTextColor.GRAY)));
        if (error != null) {
            body.add(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED)));
        }

        ActionButton save = ActionButton.create(
                Component.text("Simpan", NamedTextColor.GREEN),
                Component.text("Konfirmasi nama roleplay"),
                160,
                DialogAction.customClick((view, audience) -> {
                    String first = view.getText("first");
                    String last = view.getText("last");

                    Bukkit.getScheduler().runTask(this, () -> handleSubmit(p, first, last));
                }, ClickCallback.Options.builder().build())
        );

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("Identitas Roleplay", NamedTextColor.GOLD))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(List.of(
                                DialogInput.text("first", Component.text("Nama Pertama"))
                                        .maxLength(16).build(),
                                DialogInput.text("last", Component.text("Nama Kedua"))
                                        .maxLength(16).build()
                        ))
                        .build())
                .type(DialogType.notice(save)));

        p.showDialog(dialog);
    }

    private void handleSubmit(Player p, String first, String last) {
        if (!p.isOnline()) return;

        first = first == null ? "" : first.trim();
        last = last == null ? "" : last.trim();

        if (!VALID.matcher(first).matches() || !VALID.matcher(last).matches()) {
            openDialog(p, "Nama harus 2-16 huruf (tanpa angka/spasi). Coba lagi.");
            return;
        }

        String full = capitalize(first) + " " + capitalize(last);
        saveName(p.getUniqueId(), full);
        pending.remove(p.getUniqueId());
        applyName(p, full);
        p.sendMessage(Component.text("Nama roleplay kamu sekarang: ", NamedTextColor.GREEN)
                .append(Component.text(full, NamedTextColor.GOLD)));
    }

    // ---------- Events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String name = getSavedName(p.getUniqueId());

        if (name != null) {
            applyName(p, name);
            return;
        }

        pending.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && pending.contains(p.getUniqueId())) openDialog(p, null);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // Kunci pemain selama belum isi nama

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!pending.contains(e.getPlayer().getUniqueId())) return;
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (pending.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (pending.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // ---------- Command ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("rpname.admin")) {
                sender.sendMessage(Component.text("Kamu tidak punya izin.", NamedTextColor.RED));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Pemain harus online.", NamedTextColor.RED));
                return true;
            }
            removeName(target.getUniqueId());
            target.displayName(null);
            target.playerListName(null);
            pending.add(target.getUniqueId());
            openDialog(target, null);
            sender.sendMessage(Component.text("Nama " + target.getName() + " direset.", NamedTextColor.GREEN));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Hanya untuk pemain.");
            return true;
        }
        if (!p.hasPermission("rpname.change")) {
            p.sendMessage(Component.text("Kamu tidak punya izin.", NamedTextColor.RED));
            return true;
        }
        openDialog(p, null);
        return true;
    }
}
