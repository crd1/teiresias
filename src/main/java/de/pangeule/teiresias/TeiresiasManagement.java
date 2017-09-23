package de.pangeule.teiresias;

import java.util.List;

public class TeiresiasManagement implements TeiresiasManagementMBean {

	@Override
	public int getNumberOfPeers() {
		return Teiresias.getInstance().getNumberOfPeers();
	}

	@Override
	public void setDebugMode(boolean enabled) {
		Teiresias.setDebugMode(enabled);
	}
	
	public boolean getDebugMode() {
		return Teiresias.DEBUG_MODE;
	}

	@Override
	public void shutdown() {
		Teiresias.getInstance().shutdown();
	}

	@Override
	public boolean getIsLocalMaster() {
		return Teiresias.getInstance().isLocalMaster();
	}

	@Override
	public List<String> getTargetApps() {
		return Teiresias.getInstance().getTargetApps();
	}

}
