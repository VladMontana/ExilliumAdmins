package com.dylanok.exilliumadmins;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExilliumAdminsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String PREFIX = "§8[§6Exillium§8] §r";
    private static final int LOGS_PER_PAGE = 10;
    private static final int KICK_LOGS_PER_PAGE = 5;

    private File kickFile;
    private FileConfiguration kickConfig;
    private File mutesFile;
    private FileConfiguration mutesConfig;
    private File muteLogFile;
    private File banLogFile;
    private File warnFile;
    private FileConfiguration warnConfig;
    private File warnLogFile;

    private final Map<UUID, MuteData> mutes = new ConcurrentHashMap<>();

    private static final class MuteData {
        private long end;
        private String reason;
        private String admin;
    }

    @Override
    public void onEnable() {
        loadFiles();
        loadMutes();

        for (String command : List.of(
                "kick", "kicklog", "mute", "unmute", "checkmute", "mutelist", "mutelog", "ban", "unban",
                "warn", "unwarn", "warnlist", "warninfo", "warnlog")) {
            if (getCommand(command) != null) {
                getCommand(command).setExecutor(this);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        startMuteExpirationTask();
        startWarnExpirationTask();
        getLogger().info("ExilliumAdmins enabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "kick" -> kickCommand(sender, args);
            case "kicklog" -> kickLogCommand(sender, args);
            case "mute" -> muteCommand(sender, args);
            case "unmute" -> unmuteCommand(sender, args);
            case "checkmute" -> checkMuteCommand(sender, args);
            case "mutelist" -> muteListCommand(sender, args);
            case "mutelog" -> muteLogCommand(sender, args);
            case "ban" -> banCommand(sender, args);
            case "unban" -> unbanCommand(sender, args);
            case "warn" -> warnCommand(sender, args);
            case "unwarn" -> unwarnCommand(sender, args);
            case "warnlist" -> warnListCommand(sender, args);
            case "warninfo" -> warnInfoCommand(sender, args);
            case "warnlog" -> warnLogCommand(sender, args);
            default -> false;
        };
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        MuteData mute = mutes.get(event.getPlayer().getUniqueId());
        if (mute == null) {
            return;
        }

        if (mute.end != -1 && mute.end <= System.currentTimeMillis()) {
            Bukkit.getScheduler().runTask(this, () -> removeMute(event.getPlayer().getUniqueId()));
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            event.getPlayer().sendMessage(PREFIX + "§cВы замучены.");
            event.getPlayer().sendMessage("§7Причина: §f" + mute.reason);
            event.getPlayer().sendMessage("§7Осталось: §f" + formatRemaining(mute.end));
            event.getPlayer().sendMessage("§7Выдал: §f" + mute.admin);
        });
    }

    private void loadFiles() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        kickFile = ensureFile("kicklogs.yml");
        kickConfig = YamlConfiguration.loadConfiguration(kickFile);

        mutesFile = ensureFile("mutes.yml");
        mutesConfig = YamlConfiguration.loadConfiguration(mutesFile);
        muteLogFile = ensureFile("mutelogs.txt");
        banLogFile = ensureFile("banlogs.txt");

        warnFile = ensureFile("warns.yml");
        warnConfig = YamlConfiguration.loadConfiguration(warnFile);
        warnLogFile = ensureFile("warnlogs.txt");
    }

    private File ensureFile(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                getLogger().warning("Could not create " + name + ": " + exception.getMessage());
            }
        }
        return file;
    }

    private boolean kickCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /kick <игрок> <причина>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок не найден.");
            return true;
        }

        String admin = sender.getName();
        String reason = joinArgs(args, 1);
        String date = formatDate(System.currentTimeMillis());
        int id = kickConfig.getInt("last-id", 0) + 1;

        kickConfig.set("last-id", id);
        kickConfig.set("kicks." + id + ".player", target.getName());
        kickConfig.set("kicks." + id + ".admin", admin);
        kickConfig.set("kicks." + id + ".reason", reason);
        kickConfig.set("kicks." + id + ".date", date);
        saveConfigFile(kickConfig, kickFile);

        Bukkit.broadcastMessage("§c" + admin + " кикнул " + target.getName() + " по причине: " + reason);
        target.kickPlayer("§cВы были кикнуты с сервера\n\n§7Администратор: §f" + admin + "\n§7Причина: §f" + reason);
        return true;
    }

    private boolean kickLogCommand(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /kicklog <игрок> [страница]");
            return true;
        }

        String target = args[0];
        int page = parsePage(args, 1);
        List<String> logs = new ArrayList<>();
        ConfigurationSection kicks = kickConfig.getConfigurationSection("kicks");

        if (kicks != null) {
            for (String id : kicks.getKeys(false)) {
                String playerName = kickConfig.getString("kicks." + id + ".player", "");
                if (playerName.equalsIgnoreCase(target)) {
                    logs.add("§7[" + kickConfig.getString("kicks." + id + ".date", "-") + "] §c"
                            + kickConfig.getString("kicks." + id + ".admin", "-")
                            + " §7-> §f" + kickConfig.getString("kicks." + id + ".reason", "-"));
                }
            }
        }

        sendPagedLines(sender, logs, KICK_LOGS_PER_PAGE, page,
                "§6Кики игрока " + target, "/kicklog " + target + " ");
        return true;
    }

    private boolean muteCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /mute <ник> <время|perm> <причина>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        UUID uuid = target.getUniqueId();
        if (mutes.containsKey(uuid)) {
            sender.sendMessage("§cИгрок уже замучен.");
            return true;
        }

        long end = args[1].equalsIgnoreCase("perm") ? -1 : System.currentTimeMillis() + parseDuration(args[1]);
        if (end != -1 && end <= System.currentTimeMillis()) {
            sender.sendMessage("§cНеверный формат времени. Пример: 30m, 2h, 7d или perm.");
            return true;
        }

        String reason = joinArgs(args, 2);
        MuteData mute = new MuteData();
        mute.end = end;
        mute.reason = reason;
        mute.admin = sender.getName();
        saveMute(uuid, mute);

        if (target.isOnline() && target.getPlayer() != null) {
            Player player = target.getPlayer();
            player.sendMessage(PREFIX + "§cВы замучены.");
            player.sendMessage("§7Причина: §f" + reason);
            player.sendMessage("§7Время: §f" + formatRemaining(end));
            player.sendMessage("§7Выдал: §f" + sender.getName());
        }

        appendLog(muteLogFile, "MUTE", sender.getName(), target.getName(), reason, formatRemaining(end));
        sender.sendMessage("§aМут выдан игроку §f" + target.getName());
        return true;
    }

    private boolean unmuteCommand(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /unmute <ник>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        UUID uuid = target.getUniqueId();
        if (!mutes.containsKey(uuid)) {
            sender.sendMessage("§cИгрок не замучен.");
            return true;
        }

        removeMute(uuid);
        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage("§aС вас сняли мут.");
        }

        appendLog(muteLogFile, "UNMUTE", sender.getName(), target.getName(), "-", "-");
        sender.sendMessage("§aМут снят.");
        return true;
    }

    private boolean checkMuteCommand(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /checkmute <ник>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        MuteData mute = mutes.get(target.getUniqueId());
        if (mute == null) {
            sender.sendMessage("§aИгрок не замучен.");
            return true;
        }

        sender.sendMessage("§6Мут игрока §f" + target.getName());
        sender.sendMessage("§7Причина: §f" + mute.reason);
        sender.sendMessage("§7Осталось: §f" + formatRemaining(mute.end));
        sender.sendMessage("§7Выдал: §f" + mute.admin);
        return true;
    }

    private boolean muteListCommand(CommandSender sender, String[] args) {
        int page = parsePage(args, 0);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, MuteData> entry : mutes.entrySet()) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
            lines.add("§e- " + player.getName() + " §7| " + formatRemaining(entry.getValue().end));
        }

        sendPagedLines(sender, lines, LOGS_PER_PAGE, page, "§6Замученные игроки", "/mutelist ");
        return true;
    }

    private boolean muteLogCommand(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /mutelog <ник> [страница]");
            return true;
        }

        return sendLogCommand(sender, muteLogFile, args[0], parsePage(args, 1), "§eЛоги мутов игрока", "/mutelog ");
    }

    private boolean banCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /ban <ник> <время|perm> <причина>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        long end = args[1].equalsIgnoreCase("perm") ? -1 : System.currentTimeMillis() + parseDuration(args[1]);
        if (end != -1 && end <= System.currentTimeMillis()) {
            sender.sendMessage("§cНеверный формат времени. Пример: 30m, 2h, 7d или perm.");
            return true;
        }

        String reason = joinArgs(args, 2);
        PlayerProfile profile = createProfile(target);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
        Date expires = end == -1 ? null : new Date(end);
        String message = "§cВы забанены!\n\n§7Администратор: §f" + sender.getName()
                + "\n§7Причина: §f" + reason
                + "\n§7До: §f" + (end == -1 ? "навсегда" : formatDate(end));

        banList.addBan(profile, message, expires, "ExilliumAdmins");
        warnConfig.set(target.getUniqueId() + ".autoban-end", null);
        saveConfigFile(warnConfig, warnFile);

        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().kickPlayer(message);
        }

        appendLog(banLogFile, "BAN", sender.getName(), playerName(target), reason, end == -1 ? "perm" : formatDate(end));
        sender.sendMessage("§aИгрок §f" + playerName(target) + " §aзабанен.");
        return true;
    }

    private boolean unbanCommand(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /unban <ник>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        PlayerProfile profile = createProfile(target);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
        if (!banList.isBanned(profile)) {
            sender.sendMessage("§cИгрок не забанен.");
            return true;
        }

        banList.pardon(profile);
        warnConfig.set(target.getUniqueId() + ".autoban-end", null);
        saveConfigFile(warnConfig, warnFile);

        appendLog(banLogFile, "UNBAN", sender.getName(), playerName(target), "-", "-");
        sender.sendMessage("§aИгрок §f" + playerName(target) + " §aразбанен.");
        return true;
    }

    private boolean warnCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /warn <ник> <время> <причина>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        long duration = parseDuration(args[1]);
        if (duration <= 0) {
            sender.sendMessage("§cНеверный формат времени. Пример: 30m, 2h, 7d.");
            return true;
        }

        UUID uuid = target.getUniqueId();
        int id = warnConfig.getInt(uuid + ".last-id", 0) + 1;
        long end = System.currentTimeMillis() + duration;
        String reason = joinArgs(args, 2);

        warnConfig.set(uuid + ".last-id", id);
        warnConfig.set(uuid + ".warns." + id + ".reason", reason);
        warnConfig.set(uuid + ".warns." + id + ".end", end);
        saveConfigFile(warnConfig, warnFile);

        appendLog(warnLogFile, "WARN", sender.getName(), target.getName(), "ID " + id + " | " + reason + " | " + args[1], formatDate(end));

        if (target.isOnline() && target.getPlayer() != null) {
            Player player = target.getPlayer();
            player.sendMessage("§cВы получили варн §7(ID " + id + ")");
            player.sendMessage("§7Причина: §f" + reason);
            player.sendMessage("§7До: §f" + formatDate(end));
        }

        sender.sendMessage("§aВарн выдан (ID " + id + ").");
        checkAutoBan(target);
        return true;
    }

    private boolean unwarnCommand(CommandSender sender, String[] args) {
        if (args.length != 1 && args.length != 2) {
            sender.sendMessage("§cИспользование: /unwarn <ник> <id>");
            return true;
        }

        OfflinePlayer explicitTarget = null;
        int idArg = 0;
        if (args.length == 2) {
            explicitTarget = resolveKnownOfflinePlayer(args[0]);
            if (explicitTarget == null) {
                sender.sendMessage("§cИгрок никогда не заходил на сервер.");
                return true;
            }
            idArg = 1;
        }

        int id;
        try {
            id = Integer.parseInt(args[idArg]);
        } catch (NumberFormatException exception) {
            sender.sendMessage("§cID должен быть числом.");
            return true;
        }

        if (explicitTarget != null) {
            return removeWarn(sender, explicitTarget.getUniqueId().toString(), id);
        }

        List<String> matchingUuids = new ArrayList<>();
        for (String uuid : warnConfig.getKeys(false)) {
            String path = uuid + ".warns." + id;
            if (warnConfig.contains(path)) {
                matchingUuids.add(uuid);
            }
        }

        if (matchingUuids.isEmpty()) {
            sender.sendMessage("§cВарн с таким ID не найден.");
            return true;
        }

        if (matchingUuids.size() > 1) {
            sender.sendMessage("§cID найден у нескольких игроков. Используйте: /unwarn <ник> <id>");
            return true;
        }

        return removeWarn(sender, matchingUuids.get(0), id);
    }

    private boolean warnListCommand(CommandSender sender, String[] args) {
        int page = parsePage(args, 0);
        List<String> players = new ArrayList<>();

        for (String uuid : warnConfig.getKeys(false)) {
            ConfigurationSection warns = warnConfig.getConfigurationSection(uuid + ".warns");
            if (warns == null || warns.getKeys(false).isEmpty()) {
                continue;
            }

            OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            players.add(player.getName() + ":" + warns.getKeys(false).size());
        }

        if (players.isEmpty()) {
            sender.sendMessage("§7Нет активных варнов.");
            return true;
        }

        int maxPage = Math.max(1, (int) Math.ceil(players.size() / (double) LOGS_PER_PAGE));
        page = Math.max(1, Math.min(page, maxPage));
        int start = (page - 1) * LOGS_PER_PAGE;
        int end = Math.min(start + LOGS_PER_PAGE, players.size());

        sender.sendMessage("§eАктивные варны §7(страница " + page + "/" + maxPage + ")");
        for (int i = start; i < end; i++) {
            String[] data = players.get(i).split(":", 2);
            if (sender instanceof Player player) {
                TextComponent line = new TextComponent("§7- §f" + data[0] + " §7(" + data[1] + ")");
                line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/warninfo " + data[0]));
                line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7Нажмите, чтобы посмотреть варны")));
                player.spigot().sendMessage(line);
            } else {
                sender.sendMessage("§7- §f" + data[0] + " §7(" + data[1] + ")");
            }
        }
        sendPageNavigation(sender, page, maxPage, "/warnlist ");
        return true;
    }

    private boolean warnInfoCommand(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /warninfo <ник>");
            return true;
        }

        OfflinePlayer target = resolveKnownOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок никогда не заходил на сервер.");
            return true;
        }

        ConfigurationSection warns = warnConfig.getConfigurationSection(target.getUniqueId() + ".warns");
        if (warns == null || warns.getKeys(false).isEmpty()) {
            sender.sendMessage("§7У игрока нет активных варнов.");
            return true;
        }

        sender.sendMessage("§eВарны игрока §f" + target.getName());
        for (String id : warns.getKeys(false)) {
            sender.sendMessage("§7ID: §f" + id);
            sender.sendMessage("§7Причина: §f" + warnConfig.getString(target.getUniqueId() + ".warns." + id + ".reason", "-"));
            sender.sendMessage("§7До: §f" + formatDate(warnConfig.getLong(target.getUniqueId() + ".warns." + id + ".end")));
            sender.sendMessage(" ");
        }
        return true;
    }

    private boolean warnLogCommand(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cИспользование: /warnlog <ник> [страница]");
            return true;
        }

        return sendLogCommand(sender, warnLogFile, args[0], parsePage(args, 1), "§eЛоги варнов игрока", "/warnlog ");
    }

    private void loadMutes() {
        mutes.clear();
        for (String key : mutesConfig.getKeys(false)) {
            try {
                MuteData mute = new MuteData();
                mute.end = mutesConfig.getLong(key + ".end");
                mute.reason = mutesConfig.getString(key + ".reason", "-");
                mute.admin = mutesConfig.getString(key + ".admin", "-");
                mutes.put(UUID.fromString(key), mute);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid mute UUID in mutes.yml: " + key);
            }
        }
    }

    private void saveMute(UUID uuid, MuteData mute) {
        mutes.put(uuid, mute);
        mutesConfig.set(uuid + ".end", mute.end);
        mutesConfig.set(uuid + ".reason", mute.reason);
        mutesConfig.set(uuid + ".admin", mute.admin);
        saveConfigFile(mutesConfig, mutesFile);
    }

    private void removeMute(UUID uuid) {
        mutes.remove(uuid);
        mutesConfig.set(uuid.toString(), null);
        saveConfigFile(mutesConfig, mutesFile);
    }

    private void startMuteExpirationTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, MuteData>> iterator = mutes.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<UUID, MuteData> entry = iterator.next();
                if (entry.getValue().end == -1 || entry.getValue().end > now) {
                    continue;
                }

                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    player.sendMessage("§aС вас сняли мут.");
                }

                appendLog(muteLogFile, "UNMUTE", "AUTO", Bukkit.getOfflinePlayer(entry.getKey()).getName(), "-", "-");
                iterator.remove();
                mutesConfig.set(entry.getKey().toString(), null);
            }

            saveConfigFile(mutesConfig, mutesFile);
        }, 20L, 20L);
    }

    private void startWarnExpirationTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (String uuid : warnConfig.getKeys(false)) {
                clearExpiredAutoban(uuid);
                clearExpiredWarns(uuid);
            }

            saveConfigFile(warnConfig, warnFile);
        }, 20L, 1200L);
    }

    private void clearExpiredAutoban(String uuid) {
        if (!warnConfig.contains(uuid + ".autoban-end")) {
            return;
        }

        long end = warnConfig.getLong(uuid + ".autoban-end");
        OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
        PlayerProfile profile = createProfile(player);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);

        if (System.currentTimeMillis() < end && banList.isBanned(profile)) {
            return;
        }

        warnConfig.set(uuid + ".autoban-end", null);
    }

    private void clearExpiredWarns(String uuid) {
        ConfigurationSection warns = warnConfig.getConfigurationSection(uuid + ".warns");
        if (warns == null) {
            return;
        }

        for (String id : new HashSet<>(warns.getKeys(false))) {
            long end = warnConfig.getLong(uuid + ".warns." + id + ".end");
            if (System.currentTimeMillis() < end) {
                continue;
            }

            warnConfig.set(uuid + ".warns." + id, null);
            OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            appendLog(warnLogFile, "WARN-EXPIRE", "SYSTEM", playerName(player), "ID " + id, "-");
        }
    }

    private void checkAutoBan(OfflinePlayer player) {
        String uuid = player.getUniqueId().toString();
        clearExpiredWarns(uuid);
        if (hasActiveAutoban(player)) {
            return;
        }

        ConfigurationSection warns = warnConfig.getConfigurationSection(uuid + ".warns");
        if (warns == null || warns.getKeys(false).size() < 5) {
            return;
        }

        long end = System.currentTimeMillis() + 86_400_000L;
        Date unbanDate = new Date(end);
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
        dateFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
        timeFormat.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));

        String message = "§cВы забанены за 5/5 варнов!\n"
                + "§cВы будете разблокированы " + dateFormat.format(unbanDate)
                + " в " + timeFormat.format(unbanDate) + " по МСК";

        PlayerProfile profile = createProfile(player);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
        banList.addBan(profile, message, new Date(end), "ExilliumAdmins");
        if (player.isOnline() && player.getPlayer() != null) {
            player.getPlayer().kickPlayer(message);
        }

        warnConfig.set(player.getUniqueId() + ".autoban-end", end);
        saveConfigFile(warnConfig, warnFile);
    }

    private boolean hasActiveAutoban(OfflinePlayer player) {
        String path = player.getUniqueId() + ".autoban-end";
        if (!warnConfig.contains(path)) {
            return false;
        }

        long end = warnConfig.getLong(path);
        if (System.currentTimeMillis() >= end) {
            warnConfig.set(path, null);
            saveConfigFile(warnConfig, warnFile);
            return false;
        }

        PlayerProfile profile = createProfile(player);
        BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
        if (banList.isBanned(profile)) {
            return true;
        }

        warnConfig.set(path, null);
        saveConfigFile(warnConfig, warnFile);
        return false;
    }

    private boolean sendLogCommand(CommandSender sender, File file, String target, int page, String title, String commandPrefix) {
        List<String> allLines;
        try {
            allLines = java.nio.file.Files.readAllLines(file.toPath());
        } catch (IOException exception) {
            sender.sendMessage("§cНе удалось прочитать лог.");
            return true;
        }

        List<String> lines = new ArrayList<>();
        for (String line : allLines) {
            if (line.toLowerCase(Locale.ROOT).contains(("| " + target + " |").toLowerCase(Locale.ROOT))) {
                lines.add("§7" + line);
            }
        }
        Collections.reverse(lines);

        sendPagedLines(sender, lines, LOGS_PER_PAGE, page, title + " §f" + target, commandPrefix + target + " ");
        return true;
    }

    private void sendPagedLines(CommandSender sender, List<String> lines, int perPage, int page, String title, String commandPrefix) {
        if (lines.isEmpty()) {
            sender.sendMessage("§7Записи не найдены.");
            return;
        }

        int maxPage = Math.max(1, (int) Math.ceil(lines.size() / (double) perPage));
        page = Math.max(1, Math.min(page, maxPage));
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, lines.size());

        sender.sendMessage(title + " §7(страница " + page + "/" + maxPage + ")");
        for (int i = start; i < end; i++) {
            sender.sendMessage(lines.get(i));
        }
        sendPageNavigation(sender, page, maxPage, commandPrefix);
    }

    private void sendPageNavigation(CommandSender sender, int page, int maxPage, String commandPrefix) {
        if (!(sender instanceof Player player) || maxPage <= 1) {
            return;
        }

        TextComponent line = new TextComponent("");
        if (page > 1) {
            TextComponent previous = new TextComponent("§e← ");
            previous.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, commandPrefix + (page - 1)));
            previous.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7Предыдущая страница")));
            line.addExtra(previous);
        }

        if (page < maxPage) {
            TextComponent next = new TextComponent("§e→");
            next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, commandPrefix + (page + 1)));
            next.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7Следующая страница")));
            line.addExtra(next);
        }

        player.spigot().sendMessage(line);
    }

    private int parsePage(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }

        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private OfflinePlayer resolveKnownOfflinePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }

        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String playerName = player.getName();
            if (playerName != null && playerName.equalsIgnoreCase(name)) {
                return player;
            }
        }

        return null;
    }

    private boolean removeWarn(CommandSender sender, String uuid, int id) {
        String path = uuid + ".warns." + id;
        if (!warnConfig.contains(path)) {
            sender.sendMessage("§cВарн с таким ID у этого игрока не найден.");
            return true;
        }

        warnConfig.set(path, null);
        saveConfigFile(warnConfig, warnFile);

        OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
        if (player.isOnline() && player.getPlayer() != null) {
            player.getPlayer().sendMessage("§aС вас снят варн (ID " + id + ").");
        }

        appendLog(warnLogFile, "UNWARN", sender.getName(), playerName(player), "ID " + id, "-");
        sender.sendMessage("§aВарн снят.");
        return true;
    }

    private PlayerProfile createProfile(OfflinePlayer player) {
        String name = player.getName();
        if (name == null || name.isBlank()) {
            return Bukkit.createPlayerProfile(player.getUniqueId());
        }

        return Bukkit.createPlayerProfile(player.getUniqueId(), name);
    }

    private String playerName(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    private long parseDuration(String value) {
        if (value.length() < 2) {
            return -1;
        }

        try {
            long number = Long.parseLong(value.substring(0, value.length() - 1));
            return switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
                case 's' -> number * 1_000L;
                case 'm' -> number * 60_000L;
                case 'h' -> number * 3_600_000L;
                case 'd' -> number * 86_400_000L;
                default -> -1;
            };
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String formatRemaining(long end) {
        if (end == -1) {
            return "навсегда";
        }

        long totalMinutes = Math.max(0, (end - System.currentTimeMillis()) / 60_000L);
        if (totalMinutes < 60) {
            return totalMinutes + " мин";
        }

        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours < 24) {
            return hours + " ч " + minutes + " мин";
        }

        return (hours / 24) + " д " + (hours % 24) + " ч";
    }

    private String formatDate(long time) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(time));
    }

    private String joinArgs(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private void saveConfigFile(FileConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException exception) {
            getLogger().warning("Could not save " + file.getName() + ": " + exception.getMessage());
        }
    }

    private void appendLog(File file, String action, String admin, String player, String reason, String time) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write("[" + new Date() + "] " + action + " | " + player + " | " + reason + " | " + time + " | by " + admin + System.lineSeparator());
        } catch (IOException exception) {
            getLogger().warning("Could not write log " + file.getName() + ": " + exception.getMessage());
        }
    }
}
