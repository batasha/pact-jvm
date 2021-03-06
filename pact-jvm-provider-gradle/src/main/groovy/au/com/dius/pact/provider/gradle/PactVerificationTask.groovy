package au.com.dius.pact.provider.gradle

import au.com.dius.pact.model.Interaction
@SuppressWarnings('UnusedImport')
import au.com.dius.pact.model.Pact
import au.com.dius.pact.model.Pact$
import au.com.dius.pact.provider.groovysupport.ProviderClient
import au.com.dius.pact.provider.groovysupport.ResponseComparison
import org.apache.commons.lang3.StringUtils
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import org.gradle.api.DefaultTask
import org.gradle.api.GradleScriptException
import org.gradle.api.Task
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.TaskAction
import org.json4s.FileInput
import org.json4s.StreamInput
import scala.collection.JavaConverters$

/**
 * Task to verify a pact against a provider
 */
@SuppressWarnings('DuplicateImport')
class PactVerificationTask extends DefaultTask {

    ProviderInfo providerToVerify

    @TaskAction
    @SuppressWarnings(['AbcMetric', 'PrintStackTrace', 'DuplicateStringLiteral', 'MethodSize'])
    void verifyPact() {
        ext.failures = [:]
        providerToVerify.consumers.findAll(this.&filterConsumers).each { consumer ->
            AnsiConsole.out().println(Ansi.ansi().a('\nVerifying a pact between ').bold().a(consumer.name)
                .boldOff().a(' and ').bold().a(providerToVerify.name).boldOff())

            Pact pact
            if (consumer.pactFile instanceof File) {
                AnsiConsole.out().println(Ansi.ansi().a("  [Using file ${consumer.pactFile}]"))
                pact = Pact$.MODULE$.from(new FileInput(consumer.pactFile))
            } else if (consumer.pactFile instanceof URL) {
                AnsiConsole.out().println(Ansi.ansi().a("  [from URL ${consumer.pactFile}]"))
                pact = Pact$.MODULE$.from(new StreamInput(consumer.pactFile.newInputStream(requestProperties:
                    ['Accept': 'application/json'])))
            } else {
                throw new GradleScriptException('You must specify the pactfile to execute (use pactFile = ...)',
                    null)
            }

            def interactions = JavaConverters$.MODULE$.seqAsJavaListConverter(pact.interactions())
            interactions.asJava().findAll(this.&filterInteractions).each { Interaction interaction ->
                def interactionMessage = "Verifying a pact between ${consumer.name} and ${providerToVerify.name}" +
                    " - ${interaction.description()}"

                def stateChangeOk = true
                if (interaction.providerState.defined) {
                    stateChangeOk = stateChange(interaction.providerState.get(), consumer)
                    if (stateChangeOk != true) {
                        ext.failures[interactionMessage] = stateChangeOk
                        stateChangeOk = false
                    } else {
                        interactionMessage += " Given ${interaction.providerState.get()}"
                    }
                }

                if (stateChangeOk) {
                    AnsiConsole.out().println(Ansi.ansi().a('  ').a(interaction.description()))

                    try {
                        ProviderClient client = new ProviderClient(request: interaction.request(),
                            provider: providerToVerify)

                        def expectedResponse = interaction.response()
                        def actualResponse = client.makeRequest()

                        def comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse,
                                actualResponse.statusCode, actualResponse.headers, actualResponse.data)

                        AnsiConsole.out().println('    returns a response which')

                        def s = ' returns a response which'
                        displayMethodResult(failures, expectedResponse.status(), comparison.method,
                            interactionMessage + s)
                        displayHeadersResult(failures, expectedResponse.headers(), comparison.headers,
                            interactionMessage + s)
                        expectedResponse.body().defined ? expectedResponse.body().get() : ''
                        displayBodyResult(failures, comparison.body, interactionMessage + s)
                    } catch (e) {
                        AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a('Request Failed - ')
                            .a(e.message).reset())
                        ext.failures[interactionMessage] = e
                        if (project.hasProperty('pact.showStacktrace')) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        if (ext.failures.size() > 0) {
            AnsiConsole.out().println('\nFailures:\n')
            failures.eachWithIndex { err, i ->
                AnsiConsole.out().println("$i) ${err.key}")
                if (err.value instanceof Exception || err.value instanceof Error) {
                    err.value.message.split('\n').each {
                        AnsiConsole.out().println("      $it")
                    }
                } else if (err.value instanceof Map && err.value.containsKey('diff')) {
                    err.value.comparison.each { key, message ->
                        AnsiConsole.out().println("      $key -> $message")
                    }

                    AnsiConsole.out().println()
                    AnsiConsole.out().println('      Diff:')
                    AnsiConsole.out().println()

                    err.value.diff.each { delta ->
                        if (delta.startsWith('@')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.CYAN).a(delta).reset())
                        } else if (delta.startsWith('-')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a(delta).reset())
                        } else if (delta.startsWith('+')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.GREEN).a(delta).reset())
                        } else {
                            AnsiConsole.out().println("      $delta")
                        }
                    }
                } else if (err.value instanceof String) {
                    AnsiConsole.out().println("      ${err.value}")
                } else {
                    err.value.each { key, message ->
                        AnsiConsole.out().println("      $key -> $message")
                    }
                }
                AnsiConsole.out().println()
            }

            throw new GradleScriptException(
                "There were ${failures.size()} pact failures for provider ${providerToVerify.name}", null)
        }
    }

    @SuppressWarnings('DuplicateStringLiteral')
    void displayMethodResult(Map failures, int status, def comparison, String comparisonDescription) {
        def ansi = Ansi.ansi().a('      ').a('has status code ').bold().a(status).boldOff().a(' (')
        if (comparison == true) {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
            failures["$comparisonDescription has status code $status"] = comparison
        }
    }

    @SuppressWarnings('DuplicateStringLiteral')
    void displayHeadersResult(Map failures, def expected, Map comparison, String comparisonDescription) {
        if (!comparison.isEmpty()) {
            AnsiConsole.out().println('      includes headers')
            Map expectedHeaders = JavaConverters$.MODULE$.mapAsJavaMapConverter(expected.get()).asJava()
            comparison.each { key, headerComparison ->
                def expectedHeaderValue = expectedHeaders[key]
                def ansi = Ansi.ansi().a('        "').bold().a(key).boldOff().a('" with value "').bold()
                    .a(expectedHeaderValue).boldOff().a('" (')
                if (headerComparison == true) {
                    AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
                } else {
                    AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
                    failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] =
                        headerComparison
                }
            }
        }
    }

    @SuppressWarnings('DuplicateStringLiteral')
    void displayBodyResult(Map failures, def comparison, String comparisonDescription) {
        def ansi = Ansi.ansi().a('      ').a('has a matching body').a(' (')
        if (comparison.isEmpty()) {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
            failures["$comparisonDescription has a matching body"] = comparison
        }
    }

    @SuppressWarnings(['AbcMetric', 'NestedBlockDepth', 'PrintStackTrace', 'UnnecessaryElseStatement',
        'DuplicateStringLiteral'])
    def stateChange(String state, ConsumerInfo consumer) {
        AnsiConsole.out().println(Ansi.ansi().a('  Given ').bold().a(state).boldOff())
        try {
            def stateChangeHandler = consumer.stateChange
            if (stateChangeHandler == null || (stateChangeHandler instanceof String
                && StringUtils.isBlank(stateChangeHandler))) {
              AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.YELLOW)
                .a('WARNING: State Change ignored as there is no stateChange URL')
                .reset())
              return true
            } else if (stateChangeHandler instanceof Closure) {
              return stateChangeHandler.call(state)
            } else if (stateChangeHandler instanceof Task || stateChangeHandler instanceof String
              && project.tasks.findByName(stateChangeHandler)) {
              def task = stateChangeHandler instanceof String ? project.tasks.getByName(stateChangeHandler)
                  : stateChangeHandler
              task.setProperty('providerState', state)
              task.ext.providerState = state
              def build = project.task(type: GradleBuild) {
                tasks = [task.name]
              }
              build.execute()
              return true
            } else {
                try {
                  def url = stateChangeHandler instanceof URI ? stateChangeHandler
                      : new URI(stateChangeHandler.toString())
                  ProviderClient client = new ProviderClient(provider: providerToVerify)
                  def response = client.makeStateChangeRequest(url, state, consumer.stateChangeUsesBody)
                  if (response) {
                    try {
                      if (response.statusLine.statusCode >= 400) {
                        AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.RED)
                          .a('State Change Request Failed - ')
                          .a(response.statusLine.toString()).reset())
                        return 'State Change Request Failed - ' + response.statusLine.toString()
                      }
                    } finally {
                      response.close()
                    }
                  }
                } catch (URISyntaxException ex) {
                  AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.YELLOW)
                    .a("WARNING: State Change ignored as there is no stateChange URL, received \"$stateChangeHandler\"")
                    .reset())
                }
                return true
            }
        } catch (e) {
            AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.RED).a('State Change Request Failed - ')
                .a(e.message).reset())
            if (project.hasProperty('pact.showStacktrace')) {
                e.printStackTrace()
            }
            return e
        }
    }

  @SuppressWarnings('DuplicateStringLiteral')
  boolean filterConsumers(def consumer) {
    !project.hasProperty('pact.filter.consumers') || consumer.name in project.property('pact.filter.consumers')
        .split(',')*.trim()
  }

  @SuppressWarnings('DuplicateStringLiteral')
  boolean filterInteractions(def interaction) {
    if (project.hasProperty('pact.filter.description') && project.hasProperty('pact.filter.providerState')) {
      matchDescription(interaction) && matchState(interaction)
    } else if (project.hasProperty('pact.filter.description')) {
      matchDescription(interaction)
    } else if (project.hasProperty('pact.filter.providerState')) {
      matchState(interaction)
    } else {
      true
    }
  }

  @SuppressWarnings('DuplicateStringLiteral')
  private boolean matchState(interaction) {
    if (interaction.providerState().defined) {
      interaction.providerState().get() ==~ project.property('pact.filter.providerState')
    } else {
      project.property('pact.filter.providerState').empty
    }
  }

  @SuppressWarnings('DuplicateStringLiteral')
  private boolean matchDescription(interaction) {
    interaction.description() ==~ project.property('pact.filter.description')
  }
}
