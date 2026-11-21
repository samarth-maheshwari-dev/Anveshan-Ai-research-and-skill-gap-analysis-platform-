package dev.anveshan.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReferencesCleanupTest {

	@Test
	@DisplayName("T-16-14 IEEE: journal/institution/e-mail/digit author strings dropped, ALL-CAPS title in title case, no doi.org site")
	void cleansScrapedMetadata() {
		var r = new References.Ref("IS INDIA'S EDUCATION SYSTEM READY FOR THE REAL WORLD",
				List.of("International Journal of Modern Arts and Education Invention", "History-info@historyjournal.net",
						"Dr. Syed Sabir Hussain Ali 2021", "Rahul Kumar Sharma", "Research Review Institute"),
				"doi.org", LocalDate.of(2024, 5, 1), "https://doi.org/10.1/x", "https://doi.org/10.1/x");
		assertThat(References.ieee(1, r)).isEqualTo(
				"[1] R. K. Sharma, \"Is India's Education System Ready for the Real World,\" 2024, doi: 10.1/x.");
	}

	@Test
	@DisplayName("T-16-14 titleCase: mixed-case titles unchanged; small words lower-case inside")
	void titleCase() {
		assertThat(References.titleCase("Skills of the Workforce")).isEqualTo("Skills of the Workforce");
		assertThat(References.titleCase("SKILLS OF THE WORKFORCE IN INDIA")).isEqualTo("Skills of the Workforce in India");
		assertThat(References.personNames(List.of("A. Gupta", "Ministry of Education", "x@y.z"))).containsExactly("A. Gupta");
	}

}
