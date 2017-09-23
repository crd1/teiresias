package de.pangeule.teiresias;

import java.util.logging.Level;
import java.util.logging.Logger;

public class TeiresiasLogger {

	private static final Logger LOGGER = Logger.getLogger(Teiresias.class
			.getSimpleName());

	public void log(Level level, String message, Object... arguments) {
		if (Teiresias.DEBUG_MODE) {
			LOGGER.log(level, message, arguments);
		}
	}
}
