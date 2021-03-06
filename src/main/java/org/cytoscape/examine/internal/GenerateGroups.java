package org.cytoscape.examine.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.LimitExceededException;

import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.group.CyGroup;
import org.cytoscape.group.CyGroupFactory;
import org.cytoscape.group.CyGroupManager;
import org.cytoscape.model.CyEdge;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNode;
import org.cytoscape.model.CyRow;
import org.cytoscape.model.CyTable;
import org.cytoscape.work.Task;
import org.cytoscape.work.TaskMonitor;

/**
 * This class contains all the logic to generate groups.
 * 
 * @author melkebir
 * 
 */
public class GenerateGroups implements Task {
	private final CyApplicationManager applicationManager;
	private final CyGroupManager groupManager;
	private final CyGroupFactory groupFactory;
	private final CyNetwork network;
	private final CyTable nodeTable;
	private final List<String> selectedGroupColumnNames;
	private final boolean all;
	
	private Map<String, CyGroup> groupIndex;

	public GenerateGroups(CyApplicationManager applicationManager,
			CyGroupManager groupManager, CyGroupFactory groupFactory,
			CyNetwork network, CyTable nodeTable,
			List<String> selectedGroupColumnNames, boolean all) {
        this.applicationManager = applicationManager;
        this.groupManager = groupManager;
        this.groupFactory = groupFactory;
        this.network = network;
        this.nodeTable = nodeTable;
        this.selectedGroupColumnNames = selectedGroupColumnNames;
        this.all = all;
	}
	
    /**
     * Initialize group index.
     */
    private void initGroupIndex(CyNetwork network, CyTable nodeTable) {
        groupIndex = new HashMap<String, CyGroup>();
        Set<CyGroup> groups = groupManager.getGroupSet(network);
        
        for (CyGroup group : groups) {
        	long SUID = group.getGroupNode().getSUID();
        	CyRow row = nodeTable.getRow(SUID);
        	
        	String groupName = row.get(CyNetwork.NAME, String.class);
        	groupIndex.put(groupName, group);
        }
    }
	
    /**
     * Add/replace group with given name and member nodes.
     */
    private CyGroup addGroup(CyNetwork network, CyTable nodeTable, String groupName, List<CyNode> members) {
        // Determine whether group already exists.
        CyGroup group = groupIndex.get(groupName);
        if (group == null) {
            group = groupFactory.createGroup(network, members, new ArrayList<CyEdge>(), false);
            nodeTable.getRow(group.getGroupNode().getSUID()).set(CyNetwork.NAME, groupName);
            
            groupIndex.put(groupName, group);
        } else {
        	members.removeAll(group.getNodeList());
        	
        	//System.out.println("Adding " + members.size() + " nodes.");
        	if (members.size() > 0)
        		group.addNodes(members);
            //System.out.println("Done!");
        }
        
        return group;
    }

	@Override
	public void cancel() {
		// TODO Auto-generated method stub
	}

	@Override
	public void run(TaskMonitor taskMonitor) throws Exception {
		/*taskMonitor.setTitle("Testing...");
		for (int i = 0; i < 5; i++) {
			taskMonitor.setStatusMessage("Sleeping #" + i);
			System.out.println("Sleeping #" + i);
			Thread.sleep(5000);
			taskMonitor.setProgress((double) i / (double) 4);
		}
		return;*/
		
		if (network == null || nodeTable == null) return;

		ControlPanel.listenersEnabled.set(false);
		
		taskMonitor.setTitle("Generating groups");
		taskMonitor.setStatusMessage("Initializing group index.");
		initGroupIndex(network, nodeTable);
		
		Map<String, Map<String, List<CyNode>>> map = new HashMap<String, Map<String,List<CyNode>>>();
		for (String groupColumnName : selectedGroupColumnNames) {
			map.put(groupColumnName, new HashMap<String, List<CyNode>>());
		}
		
		taskMonitor.setStatusMessage("Extracting groups from selected columns.");
		// iterate over all rows, skipping group nodes
		List<CyRow> rows = nodeTable.getAllRows();
		for (CyRow row : rows) {
			CyNode node = network.getNode(row.get(CyNetwork.SUID, Long.class));
			
			// skip group nodes
			if (node == null || groupManager.isGroup(node, network)) continue;
			// skip nodes that are not selected if !all
			if (!all && !row.get(CyNetwork.SELECTED, Boolean.class)) continue;
			
			// iterate over all columns representing different categories
			for (String groupColumnName : selectedGroupColumnNames) {
				
				Map<String, List<CyNode>> mapGroup = map.get(groupColumnName);
				//System.out.println(groupColumnName + ": " + node.getSUID());
				
				// iterate over all values in the current list column
				List<String> values = row.getList(groupColumnName, String.class, new ArrayList<String>());
				if (values != null) {
					for (String value : values) {
						List<CyNode> list = mapGroup.get(value);
						if (list == null) {
							list = new ArrayList<CyNode>();
							mapGroup.put(value, list);
						}
						
						// add node to the group
						list.add(node);
					}
				}
			}
		}
		
		// now we create the actual groups
		int i = 0;
		for (String groupColumnName : selectedGroupColumnNames) {
			Map<String, List<CyNode>> mapGroup = map.get(groupColumnName);
			
			List<CyNode> subGroupNodes = new ArrayList<CyNode>();
			Set<String> keys = mapGroup.keySet();
			for (String groupName : keys) {
                List<CyNode> group = mapGroup.get(groupName);
                
    			taskMonitor.setStatusMessage("Adding group " + groupName + " " + " with " + group.size() + " entries.");
    			//System.out.println("Adding group " + groupColumnName + ": " + groupName + " with " + group.size() + " entries.");
                CyGroup subGroup = addGroup(network, nodeTable, groupName, group);
                subGroupNodes.add(subGroup.getGroupNode());
            }
			
			// Add column group.
			taskMonitor.setStatusMessage("Adding column group " + groupColumnName + " with " + subGroupNodes.size() + " entries.");
			//System.out.println("Adding column group " + groupColumnName + " with " + subGroupNodes.size() + " entries.");
			addGroup(network, nodeTable, Constants.CATEGORY_PREFIX + groupColumnName, subGroupNodes);
			
			i++;
			taskMonitor.setProgress((double) i / (double) selectedGroupColumnNames.size());
		}
		
		// now finalize groups
		taskMonitor.setStatusMessage("Finalizing groups.");
		groupManager.addGroups(new ArrayList<CyGroup>(groupIndex.values()));
		
		ControlPanel.listenersEnabled.set(true);
	}
}
