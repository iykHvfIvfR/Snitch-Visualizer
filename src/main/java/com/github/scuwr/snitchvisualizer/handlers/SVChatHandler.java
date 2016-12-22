package com.github.scuwr.snitchvisualizer.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.github.scuwr.snitchvisualizer.SV;
import com.github.scuwr.snitchvisualizer.classobjects.Block;
import com.github.scuwr.snitchvisualizer.classobjects.Snitch;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wafflestomper.wafflecore.WaffleCore;

/**
 * Chat handler for Snitch Visualizer
 *
 * @author Scuwr
 *
 */
public class SVChatHandler {

	public static boolean updateSnitchList = false;
	public static boolean snitchReport = false;
	public static String snitchReportName = "";
	public static int jalistIndex = 1;
	public static int jainfoIndex = 1;
	public static ArrayList<Block> tempList;

	private static Logger logger = LogManager.getLogger("SnitchVisualizer");
	private static Pattern snitchMessage = Pattern.compile(
			"\\s*World:\\s*([^\\s]*)" +
			"\\s*Location:\\s*\\[([-\\d]*)\\s*([-\\d]*)\\s*([-\\d]*)\\]" +
			"\\s*Hours to cull:\\s*(\\d*.\\d\\d)?" +
			"\\s*Group:\\s*([^\\s]*)" +
			"\\s*Name:\\s*([^\\s]*)");

	@SubscribeEvent
	public void onClientChat(ClientChatReceivedEvent event) {
		if (event == null || event.getMessage() == null) {
			return;
		}
		String msg = event.getMessage().getUnformattedText();
		if (msg == null) {
			return;
		}
		String jsonMsg = ITextComponent.Serializer.componentToJson(event.getMessage());
		jsonMsg = parseJalistJSON(jsonMsg);
		if (jsonMsg != null) {
			handleSnitchChatJsonMsg(jsonMsg);
		} else {
			handleSnitchChatMsg(msg);
		}
	}

	private String parseJalistJSON(String json) {
		JsonElement jelement = new JsonParser().parse(json);
		JsonObject jobject = jelement.getAsJsonObject();
		if (!jobject.has("extra")) {
			return null;
		}
		JsonArray jarray = jobject.getAsJsonArray("extra");
		jobject = jarray.get(0).getAsJsonObject();
		if (!jobject.has("extra")) {
			return null;
		}
		jarray = jobject.getAsJsonArray("extra");
		String result = "";
		for (JsonElement line : jarray) {
			jobject = line.getAsJsonObject();
			if (!jobject.has("hoverEvent")) {
				continue;
			}
			jobject = jobject.getAsJsonObject("hoverEvent");
			if (!jobject.has("value")) {
				continue;
			}
			jobject = jobject.getAsJsonObject("value");
			if (!jobject.has("text")) {
				continue;
			}
			String text = jobject.get("text").toString();
			text = text.replaceAll("\\\\n", " ").replaceAll("^\"+", "").replaceAll("\"+$", "");
			result += text + "\n";
		}
		return result;
	}

	private void handleSnitchChatJsonMsg(String jsonMsg) {
		String lines[] = jsonMsg.split("\\n");
		for (String line : lines) {
			Matcher isSM = SVChatHandler.snitchMessage.matcher(line);
			if (isSM.matches()) {
				parseSnitch(line, isSM);
			}
		}
	}

	private void handleSnitchChatMsg(String msg) {
		if (msg.contains("*") && msg.contains("snitch at")) {
			// parseSnitch(msg)
		} else if (msg.contains("Used") || msg.contains("Block Break") || msg.contains("Block Place")) {
			// render block place/break/use
			parseBlock(msg);
		} else if (msg.contains("Entry")) {
			if (snitchReport)
				parseEntry(msg);
		} else if (msg.contains("Snitch Log for") || msg.contains("Page 1 is empty for snitch")) {
			// export jainfo to csv
			try {
				String name = parseSnitchName(msg);
				if (SVPlayerHandler.updateSnitchName) {
					SVPlayerHandler.updateSnitchName = false; // done!
				}
				SVFileIOHandler.saveList();

				if (snitchReport) {
					if (snitchReportName.equals("")) {
						snitchReportName = name;
					}
					if (!snitchReportName.equals(name)) {
						snitchReport = false;
						SVFileIOHandler.saveSnitchReport(snitchReportName);
						snitchReportName = "";
					}
				}
			} catch (Exception e) {
				logger.error("Exception encountered while parsing snitch logs", e);

				try {
					Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
							"non-fatal error while parsing snitch logs"));
				} catch (Exception f) {
					logger.error("Couldn't notify player of error", f);
				}
			}
		} else if (msg.contains("Unknown command") || msg.contains(" is empty") || msg.contains("You do not own any snitches nearby!")) {
			jalistIndex = 1;
			jainfoIndex = 1;
			updateSnitchList = false;
			if (snitchReport) {
				snitchReport = false;
				SVFileIOHandler.saveSnitchReport(snitchReportName);
				snitchReportName = "";
			}
		} else if (msg.contains("TPS from last 1m, 5m, 15m:")) {
			ParseTPS(msg);
		}
	}

	public static void updateSnitchList() {
		try {
			Minecraft.getMinecraft().thePlayer.sendChatMessage("/tps");
			SVTickHandler.start = new Date();
			updateSnitchList = true;
		} catch (Exception e) {
			logger.error("Exception encountered while updating snitch list", e);
			try {
				Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
						"non-fatal error while updating snitch list"));
			} catch (Exception f) {
				logger.error("Couldn't notify player of error", f);
			}
		}
	}

	public void ParseTPS(String msg) {
		msg = msg.substring(msg.indexOf(':') + 1);
		String[] tokens = msg.split(", +"); // " 11.13, 11.25, 11.32"
		if (tokens.length > 2) {
			double a = 20.0;
			double b = 20.0;
			double c = 20.0;
			try {
				for (int i=0; i<3; i++) // fix for TPS 20 -- *20.0 is rendered, yikes.
					tokens[i] = tokens[1].replace('*', ' ');
				a = Double.parseDouble(tokens[0]);
				b = Double.parseDouble(tokens[1]);
				c = Double.parseDouble(tokens[2]);
			} catch (Exception e) {
				logger.error("Failed to parse TPS:" + e.getMessage());
			}
			try {
				if (a < b && a < c) {
					SVTickHandler.waitTime = SVTickHandler.tickTimeout / a;
				} else if (b < a && b < c) {
					SVTickHandler.waitTime = SVTickHandler.tickTimeout / b;
				} else {
					SVTickHandler.waitTime = SVTickHandler.tickTimeout / c;
				}
			} catch (Exception e) {
				logger.error("Exception encountered while parsing TPS", e);
				try {
					Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
							"non-fatal error while parsing TPS"));
				} catch (Exception f) {
					logger.error("Couldn't notify player of error", f);
				}
				SVTickHandler.waitTime = 4.0;
			}
		} else {
			SVTickHandler.waitTime = 4.0;
		}

		try {
			Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Timeout between commands is "
					+ Double.toString(SVTickHandler.waitTime) + " seconds."));
		} catch (Exception e) {
			logger.error("Exception encountered while alerting user of TPS update speed", e);
			try {
				Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
						"non-fatal error while alerting user of TPS update speed"));
			} catch (Exception f) {
				logger.error("Couldn't notify player of error", f);
			}
		}
	}

	public void parseSnitch(String msg, Matcher match) {
		if (msg == null) return;
		if (match == null) {
			match = SVChatHandler.snitchMessage.matcher(msg);
			if (!match.matches()) return;
		}
		logger.info("Parsing string | " + msg);
		try {
			String worldName = match.group(1);
			String worldUUID = WaffleCore.INSTANCE.worldInfo.getWorldName();

			int x = Integer.parseInt(match.group(2));
			int y = Integer.parseInt(match.group(3));
			int z = Integer.parseInt(match.group(4));
			double cullTime = -1.0d;
			if (match.group(5) != null) {
				cullTime = Double.parseDouble(match.group(5));
			}
			String ctGroup = match.group(6);
			String snitchName = match.group(7);

			Snitch n = new Snitch(worldName, worldUUID, x, y, z, cullTime, ctGroup, snitchName);

			// TODO: just use a sorted collection, this is gross.
			int index = Collections.binarySearch(SV.instance.snitchList, n);
			if (index < 0 || 0 != n.compareTo(SV.instance.snitchList.get(index))) {
				SV.instance.snitchList.add(n);
				Collections.sort(SV.instance.snitchList);
				SVFileIOHandler.saveList();
			} else {
				SV.instance.snitchList.get(index).setCtGroup(ctGroup);
				SV.instance.snitchList.get(index).setCullTime(cullTime);
				SVFileIOHandler.saveList();
			}
		} catch (NumberFormatException e) {
			logger.error("Failed to parse snitch from chat!");
		} catch (NullPointerException e) {
			logger.error("Failed to create snitch instance!");
		} catch (Exception e) {
			logger.error("Catchall for failures: ", e);
		}
	}

	public void parseBlock(String msg) {
		if (msg != null && msg.contains(">")) {
			try {
				msg = msg.substring(msg.indexOf(">") + 1);
				logger.info("Parsing string " + msg);
				String[] tokens = msg.split(" +|\\[|\\]");
				Block.Action type = Block.Action.NOP;
				if (tokens[2].equals("Used")) {
					type = Block.Action.USED;
				} else if (tokens[1].equals("Removed")) {
					type = Block.Action.REMOVED;
				} else if (tokens[1].equals("Placed")) {
					type = Block.Action.PLACED;
				} else if (tokens[1].equals("Exchanged")) {
					type = Block.Action.EXCHANGE;
				} else if (tokens[1].equals("Destroyed")) {
					type = Block.Action.DESTROYED;
				}

				try {
					int x = Integer.parseInt(tokens[5]);
					int y = Integer.parseInt(tokens[6]);
					int z = Integer.parseInt(tokens[7]);

					if (type != Block.Action.NOP) {
						if (!snitchReport) {
							SV.instance.blockList.add(new Block(x, y, z, type, tokens[1], "BlockID: " + tokens[4]));
						} else {
							tempList.add(new Block(x, y, z, type, tokens[1], "BlockID: " + tokens[4]));
						}
					}
				} catch (NumberFormatException e) {
					logger.error("Failed to parse block from chat!");
				} catch (NullPointerException e) {
					logger.error("Failed to create block instance!");
				}
			} catch (Exception e) {
				logger.error("Exception encountered while parsing snitch report", e);
				try {
					Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
							"non-fatal error while parsing snitch report"));
				} catch (Exception f) {
					logger.error("Couldn't notify player of error", f);
				}
			}
		}
	}

	public void parseEntry(String msg) {
		if (msg != null) {
			try {
				logger.info("Parsing string " + msg);
				String[] tokens = msg.split(" +");
				if (tokens[2].equals("Entry")) {
					tempList.add(new Block(0, 0, 0, Block.Action.ENTRY, tokens[1], tokens[3] + " " + tokens[4]));
				}
			} catch (Exception e) {
				logger.error("Exception encountered while parsing snitch entry", e);
				try {
					Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
							"non-fatal error while parsing snitch entry"));
				} catch (Exception f) {
					logger.error("Couldn't notify player of error", f);
				}
			}
		}
	}

	public String parseSnitchName(String msg) {
		try {
			if (msg != null && msg.length() > 0) {
				String tokens[] = msg.split(" +|-+");
				return tokens[tokens.length - 1];
			}
		} catch (Exception e) {
			logger.error("Exception encountered while parsing snitch name", e);
			try {
				Minecraft.getMinecraft().thePlayer.addChatMessage(new TextComponentString("Check logs; " +
						"non-fatal error while parsing snitch name"));
			} catch (Exception f) {
				logger.error("Couldn't notify player of error", f);
			}
		}
		return "Unknown";
	}
}
