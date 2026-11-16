package expo.modules.callkittelecom.conversations

import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Copies only bounded numeric diagnostics into the failure transported by the test runner. */
internal class MediaQualificationFailureRule : TestRule {
    var snapshot: () -> String = { mediaQualificationTrace() }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try { base.evaluate() }
            catch (skip: AssumptionViolatedException) { throw skip }
            catch (error: Throwable) { throw mediaQualificationAssertion(error, snapshot) }
        }
    }
}
