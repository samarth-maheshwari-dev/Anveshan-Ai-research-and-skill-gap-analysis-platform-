package dev.anveshan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

	static final ApplicationModules MODULES = ApplicationModules.of(Application.class);

	@Test
	@DisplayName("T-01-01 ApplicationModules.verify() passes with all modules")
	void verifiesModuleStructure() {
		MODULES.verify();

		List<String> names = MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString).toList();
		assertThat(names).containsExactlyInAnyOrder(
				"identity", "byok", "research", "sources", "report", "knowledge", "skills",
				"team", "notify", "search", "export", "mcp", "shared");
	}

}
