/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package help.screenshot;

import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.junit.Test;

import docking.*;
import docking.action.DockingActionIf;
import ghidra.app.plugin.core.codebrowser.CodeViewerProvider;
import ghidra.util.table.GhidraTable;

public class DataPluginScreenShots extends GhidraScreenShotGenerator {

	public DataPluginScreenShots() {
		super();
	}

	@Test
	public void testCreateStructureDialog() {
		positionListingTop(0x40d3a4);
		makeSelection(0x40d3a4, 0x40d3ab);
		performAction("Create Structure", "DataPlugin", false);
		DialogComponentProvider dialog = getDialog();
		JRadioButton button = (JRadioButton) getInstanceField("exactMatchButton", dialog);
		setSelected(button, true);
		captureDialog(500, 400);
	}

	@Test
	public void testEditFieldDialog() {
		positionListingTop(0x400080);
		positionCursor(0x400080, "+");
		leftClickCursor();
		positionListingTop(0x4000a4);
		performAction("Edit Field", "DataPlugin", false);
		captureDialog();
	}

	@Test
	public void testCreateStructureDialogWithTableSelection() {
		positionListingTop(0x40d3a4);
		makeSelection(0x40d3a4, 0x40d3ab);
		performAction("Create Structure", "DataPlugin", false);

		DialogComponentProvider dialog = getDialog();
		JRadioButton button = (JRadioButton) getInstanceField("exactMatchButton", dialog);
		setSelected(button, true);
		GhidraTable table = (GhidraTable) getInstanceField("matchingStructuresTable", dialog);
		selectRow(table, 2);

		shrinkCategoryColumn(table);

		captureDialog(600, 500);
	}

	private void shrinkCategoryColumn(JTable table) {

		runSwing(() -> {
			TableColumnModel columnModel = table.getColumnModel();
			int columnIndex = columnModel.getColumnIndex("Category");
			TableColumn column = columnModel.getColumn(columnIndex);
			int size = 150;
			column.setPreferredWidth(size);
			column.setMaxWidth(size);
		});
	}

	@Test
	public void testDataSelectionSettings() {
		positionListingTop(0x40d3a4);
		makeSelection(0x40d3a4, 0x40d3ab);
		performAction("Data Settings", "DataPlugin", false);
		captureDialog();
	}

	@Test
	public void testDefaultSettings() {
		positionListingTop(0x40d3a4);
		ComponentProvider componentProvider = getProvider(CodeViewerProvider.class);
		ActionContext actionContext = componentProvider.getActionContext(null);
		DockingActionIf action = getAction("Default Settings", actionContext);
		performAction(action, actionContext, false);
		captureDialog();
	}

	@Test
	public void testInstanceSettings() {
		positionListingTop(0x40d3a4);
		performAction("Data Settings", "DataPlugin", false);
		captureDialog();
	}

	private DockingActionIf getAction(String name, ActionContext context) {
		Set<DockingActionIf> actions = getDataPluginActions(context);
		for (DockingActionIf element : actions) {
			String actionName = element.getName();
			int pos = actionName.indexOf(" (");
			if (pos > 0) {
				actionName = actionName.substring(0, pos);
			}
			if (actionName.equals(name)) {
				return element;
			}
		}
		return null;
	}

	private Set<DockingActionIf> getDataPluginActions(ActionContext context) {
		Set<DockingActionIf> actions = getActionsByOwner(tool, "DataPlugin");
		if (context == null) {
			return actions;
		}
		// assumes returned set may be modified
		return actions.stream()
				.filter(a -> a.isValidContext(context))
				.collect(Collectors.toSet());
	}
}
