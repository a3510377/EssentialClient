package essentialclient.clientscript.extensions;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import essentialclient.clientscript.ClientScript;
import essentialclient.clientscript.events.MinecraftScriptEvent;
import essentialclient.clientscript.events.MinecraftScriptEvents;
import essentialclient.clientscript.values.*;
import essentialclient.config.clientrule.ClientRuleHelper;
import essentialclient.utils.command.CommandHelper;
import essentialclient.utils.interfaces.ChatHudAccessor;
import me.senseiwells.arucas.api.IArucasExtension;
import me.senseiwells.arucas.throwables.CodeError;
import me.senseiwells.arucas.throwables.RuntimeError;
import me.senseiwells.arucas.utils.Context;
import me.senseiwells.arucas.values.*;
import me.senseiwells.arucas.values.functions.AbstractBuiltInFunction;
import me.senseiwells.arucas.values.functions.FunctionValue;
import me.senseiwells.arucas.values.functions.MemberFunction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

public class ArucasMinecraftClientMembers implements IArucasExtension {

	@Override
	public Set<? extends AbstractBuiltInFunction<?>> getDefinedFunctions() {
		return this.minecraftClientFunctions;
	}

	@Override
	public String getName() {
		return "MinecraftClientMemberFunctions";
	}

	private final Set<? extends AbstractBuiltInFunction<?>> minecraftClientFunctions = Set.of(
		new MemberFunction("screenshot", this::screenshot),
		new MemberFunction("clearChat", this::clearChat),
		new MemberFunction("getLatestChatMessage", this::getLatestChatMessage),
		new MemberFunction("addCommand", List.of("commandName", "arguments"), this::addCommand),
		new MemberFunction("isInSinglePlayer", (context, function) -> new BooleanValue(this.getClient(context, function).isInSingleplayer())),
		new MemberFunction("getServerName", this::getServerName),
		new MemberFunction("getScriptsPath", (context, function) -> new StringValue(ClientScript.getDir().toString())),

		new MemberFunction("getPlayer", this::getPlayer),
		new MemberFunction("getWorld", this::getWorld),

		new MemberFunction("addGameEvent", List.of("eventName", "function"), this::addGameEvent),
		new MemberFunction("removeGameEvent", List.of("eventName", "id"), this::removeGameEvent),
		new MemberFunction("removeAllGameEvents", this::removeAllGameEvents),
		new MemberFunction("itemFromString", "name", this::itemFromString),
		new MemberFunction("blockFromString", "name", this::blockFromString)
	);

	private Value<?> screenshot(Context context, MemberFunction function) throws CodeError {
		MinecraftClient client = this.getClient(context, function);
		ScreenshotUtils.saveScreenshot(
			client.runDirectory,
			client.getWindow().getWidth(),
			client.getWindow().getHeight(),
			client.getFramebuffer(),
			text -> client.execute(() -> client.inGameHud.getChatHud().addMessage(text))
		);
		return new NullValue();
	}

	private Value<?> clearChat(Context context, MemberFunction function) throws CodeError {
		this.getClient(context, function).inGameHud.getChatHud().clear(true);
		return new NullValue();
	}

	private Value<?> getLatestChatMessage(Context context, MemberFunction function) throws CodeError {
		final ChatHudLine<?>[] chat = ((ChatHudAccessor) this.getClient(context, function).inGameHud.getChatHud()).getMessages().toArray(ChatHudLine[]::new);
		if (chat.length == 0) {
			return new NullValue();
		}
		return new StringValue(((Text) chat[0].getText()).getString());
	}

	private Value<?> addCommand(Context context, MemberFunction function) throws CodeError {
		StringValue stringValue = function.getParameterValueOfType(context, StringValue.class, 1);
		ListValue listValue = function.getParameterValueOfType(context, ListValue.class, 2);

		CommandHelper.functionCommands.add(stringValue.value);
		LiteralCommandNode<ServerCommandSource> command = CommandManager.literal(stringValue.value).build();
		List<ArgumentCommandNode<ServerCommandSource, String>> arguments = new ArrayList<>();

		int i = 1;
		for (Value<?> value : listValue.value) {
			if (!(value instanceof ListValue suggestionListValue)) {
				throw new RuntimeError("You must pass in a list of lists as parameter 2 for addCommand()", function.syntaxPosition, context);
			}
			List<String> suggestionList = new ArrayList<>();
			for (Value<?> suggestion : suggestionListValue.value) {
				suggestionList.add(suggestion.toString());
			}
			arguments.add(CommandManager.argument("arg" + i, StringArgumentType.string()).suggests((c, b) -> CommandSource.suggestMatching(suggestionList.toArray(String[]::new), b)).build());
			i++;
		}

		ListIterator<ArgumentCommandNode<ServerCommandSource, String>> listIterator = arguments.listIterator(arguments.size());

		ArgumentCommandNode<ServerCommandSource, String> finalArguments = null;
		while (listIterator.hasPrevious()) {
			ArgumentCommandNode<ServerCommandSource, String> argument = listIterator.previous();
			if (finalArguments == null) {
				finalArguments = argument;
				continue;
			}
			argument.addChild(finalArguments);
			finalArguments = argument;
		}
		if (finalArguments != null) {
			command.addChild(finalArguments);
		}

		CommandHelper.functionCommandNodes.add(command);
		MinecraftClient client = this.getClient(context, function);
		ClientPlayerEntity player = ArucasMinecraftExtension.getPlayer(client);
		client.execute(() -> player.networkHandler.onCommandTree(ClientRuleHelper.serverPacket));
		return new NullValue();
	}

	private Value<?> getServerName(Context context, MemberFunction function) throws CodeError {
		MinecraftClient client = this.getClient(context, function);
		ServerInfo serverInfo = client.getCurrentServerEntry();
		if (serverInfo == null) {
			throw new RuntimeError("Failed to get server name", function.syntaxPosition, context);
		}
		return new StringValue(serverInfo.name);
	}

	private Value<?> addGameEvent(Context context, MemberFunction function) throws CodeError {
		String eventName = function.getParameterValueOfType(context, StringValue.class, 1).value;
		FunctionValue functionValue = function.getParameterValueOfType(context, FunctionValue.class, 2);
		MinecraftScriptEvent event = MinecraftScriptEvents.getEvent(eventName);
		if (event == null) {
			throw new RuntimeError("The event name must be a predefined event", function.syntaxPosition, context);
		}
		return new NumberValue(event.addFunction(context, functionValue));
	}

	private Value<?> removeGameEvent(Context context, MemberFunction function) throws CodeError {
		String eventName = function.getParameterValueOfType(context, StringValue.class, 1).value;
		int eventId = function.getParameterValueOfType(context, NumberValue.class, 2).value.intValue();
		MinecraftScriptEvent event = MinecraftScriptEvents.getEvent(eventName);
		if (event == null) {
			throw new RuntimeError("The event name must be a predefined event", function.syntaxPosition, context);
		}
		if (!event.removeFunction(eventId)) {
			throw new RuntimeError("Invalid eventId", function.syntaxPosition, context);
		}
		return new NullValue();
	}

	private Value<?> removeAllGameEvents(Context context, MemberFunction function) {
		MinecraftScriptEvents.clearEventFunctions();
		return new NullValue();
	}

	private Value<?> itemFromString(Context context, MemberFunction function) throws CodeError {
		StringValue stringValue = function.getParameterValueOfType(context, StringValue.class, 1);
		return new ItemStackValue(Registry.ITEM.get(ArucasMinecraftExtension.getIdentifier(context, function.syntaxPosition, stringValue.value)).getDefaultStack());
	}

	private Value<?> blockFromString(Context context, MemberFunction function) throws CodeError {
		StringValue stringValue = function.getParameterValueOfType(context, StringValue.class, 1);
		return new BlockStateValue(Registry.BLOCK.get(ArucasMinecraftExtension.getIdentifier(context, function.syntaxPosition, stringValue.value)).getDefaultState());
	}

	private Value<?> getPlayer(Context context, MemberFunction function) throws CodeError {
		MinecraftClient client = this.getClient(context, function);
		return new PlayerValue(ArucasMinecraftExtension.getPlayer(client));
	}

	private Value<?> getWorld(Context context, MemberFunction function) throws CodeError {
		MinecraftClient client = this.getClient(context, function);
		return new WorldValue(ArucasMinecraftExtension.getWorld(client));
	}

	private MinecraftClient getClient(Context context, MemberFunction function) throws CodeError {
		MinecraftClient client = function.getParameterValueOfType(context, MinecraftClientValue.class, 0).value;
		if (client == null) {
			throw new RuntimeError("Client was null", function.syntaxPosition, context);
		}
		return client;
	}
}
