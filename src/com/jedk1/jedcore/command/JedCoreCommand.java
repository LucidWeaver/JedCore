package com.jedk1.jedcore.command;

import com.jedk1.jedcore.JedCore;
import com.projectkorra.projectkorra.command.PKCommand;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JedCoreCommand extends PKCommand {
	private static final String DOWNLOAD_URL = "https://github.com/LucidWeaver/JedCore";
	private static final String AUTHOR_COMMITS_URL = DOWNLOAD_URL + "/commits?author=";
	private static final Map<String, String> AUTHOR_IDS = Map.of("jedk1", "s3xi");
	private static final List<String> MODIFIERS = List.of("plushmonkey");
	private static final List<String> PREVIOUS_MAINTAINERS = List.of("Aztlon", "CozmycDev");
	private static final List<String> CONTRIBUTORS = List.of("0ct0ber", "chandlerpl", "Dreig-Michihi", "EtherealMC-Bit", "greenwolf5", "Manu585", "Nysseus", "PhanaticD", "PrimordialMoros", "Simplicitee", "smmy-ohd", "Snowy2174", "StrangeOne101");

	public JedCoreCommand() {
		super("jedcore", "/bending jedcore", "This command will show the statistics and version of JedCore.", new String[] { "jedcore", "jc" });
	}

	@Override
	public void execute(CommandSender sender, List<String> args) {
		if (!correctLength(sender, args.size(), 0, 1) || (!hasPermission(sender) && !isSenderJedCoreDev(sender))) {
			return;
		}
		if (args.size() == 0) {
			sendBuildInfo(sender);
		} else if (args.size() == 1 && (hasPermission(sender, "debug") || isSenderJedCoreDev(sender))) {
			//Dev commands for debugging etc.
			if (args.get(0).equalsIgnoreCase("refresh")) {
				sender.sendMessage(ChatColor.AQUA + "Jedcore refreshed.");
			}
		} else {
			help(sender, false);
		}
	}

	public static void sendBuildInfo(CommandSender sender) {
		sender.sendMessage(ChatColor.GRAY + "Running JedCore Build: " + ChatColor.RED + JedCore.plugin.getDescription().getVersion());
		sendProfileLine(sender, "Developed by: ", JedCore.plugin.getDescription().getAuthors());
		sendProfileLine(sender, "Modified by: ", MODIFIERS);
		sendProfileLine(sender, "Previous Maintainers: ", PREVIOUS_MAINTAINERS);
		sendProfileLine(sender, "Contributors: ", CONTRIBUTORS);
		sendProfileLine(sender, "Current Maintainer: ", List.of(JedCore.CURRENT_MAINTAINER));
		sendUrlLine(sender);
	}

	private static void sendProfileLine(CommandSender sender, String label, List<String> profiles) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(ChatColor.GRAY + label + ChatColor.RED + String.join(", ", profiles));
			return;
		}

		TextComponent line = new TextComponent(label);
		line.setColor(net.md_5.bungee.api.ChatColor.GRAY);
		for (int index = 0; index < profiles.size(); index++) {
			if (index > 0) {
				TextComponent separator = new TextComponent(", ");
				separator.setColor(net.md_5.bungee.api.ChatColor.RED);
				line.addExtra(separator);
			}

			String profile = profiles.get(index);
			TextComponent name = new TextComponent(profile);
			name.setColor(net.md_5.bungee.api.ChatColor.RED);
			name.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, AUTHOR_COMMITS_URL + AUTHOR_IDS.getOrDefault(profile, profile)));
			line.addExtra(name);
		}
		((Player) sender).spigot().sendMessage(line);
	}

	private static void sendUrlLine(CommandSender sender) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(ChatColor.GRAY + "URL: " + ChatColor.RED + ChatColor.ITALIC + DOWNLOAD_URL);
			return;
		}

		TextComponent line = new TextComponent("URL: ");
		line.setColor(net.md_5.bungee.api.ChatColor.GRAY);
		TextComponent url = new TextComponent(DOWNLOAD_URL);
		url.setColor(net.md_5.bungee.api.ChatColor.RED);
		url.setItalic(true);
		url.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, DOWNLOAD_URL));
		line.addExtra(url);
		((Player) sender).spigot().sendMessage(line);
	}
	
	private boolean isSenderJedCoreDev(CommandSender sender) {
		UUID[] devs = {
				UUID.fromString("4eb6315e-9dd1-49f7-b582-c1170e497ab0"),
				UUID.fromString("d57565a5-e6b0-44e3-a026-979d5de10c4d"),
				UUID.fromString("e98a2f7d-d571-4900-a625-483cbe6774fe")
		};
		if (sender instanceof Player) {
			Player player = (Player) sender;
			if (Arrays.asList(devs).contains(player.getUniqueId())) {
				return true;
			}
		}
		return false;
	}
}
