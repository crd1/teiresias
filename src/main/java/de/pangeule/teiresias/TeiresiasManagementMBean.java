package de.pangeule.teiresias;

import java.util.List;

public interface TeiresiasManagementMBean {

	void setDebugMode(boolean enabled);
	boolean getDebugMode();
	boolean getIsLocalMaster();
	List<String> getTargetApps();
	void shutdown();
	int getNumberOfPeers();
}
