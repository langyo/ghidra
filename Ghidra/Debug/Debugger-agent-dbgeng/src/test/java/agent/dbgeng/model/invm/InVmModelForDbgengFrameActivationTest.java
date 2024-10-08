/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package agent.dbgeng.model.invm;

import org.junit.Ignore;

import agent.dbgeng.model.AbstractModelForDbgengFrameActivationTest;
import ghidra.dbg.util.PathPattern;
import ghidra.dbg.util.PathUtils;

@Ignore("deprecated")
public class InVmModelForDbgengFrameActivationTest
		extends AbstractModelForDbgengFrameActivationTest {

	@Override
	protected PathPattern getStackPattern() {
		return new PathPattern(PathUtils.parse("Sessions[0].Processes[].Threads[].Stack[]"));
	}

	@Override
	public ModelHost modelHost() throws Throwable {
		return new InVmDbgengModelHost();
	}
}
