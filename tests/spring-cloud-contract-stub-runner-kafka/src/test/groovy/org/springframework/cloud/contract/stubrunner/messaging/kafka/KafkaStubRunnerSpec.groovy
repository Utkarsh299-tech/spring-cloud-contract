/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.contract.stubrunner.messaging.kafka

import java.util.function.Function

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.header.Headers
import spock.lang.IgnoreIf
import spock.lang.Specification

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootContextLoader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.contract.spec.Contract
import org.springframework.cloud.contract.stubrunner.StubFinder
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.ContextConfiguration

/**
 * @author Marcin Grzejszczak
 */
@ContextConfiguration(classes = Config, loader = SpringBootContextLoader)
@SpringBootTest(properties = ["debug=true"])
@AutoConfigureStubRunner
@IgnoreIf({ os.windows })
@EmbeddedKafka(topics = ["input", "output", "delete"])
class KafkaStubRunnerSpec extends Specification {

	@Autowired
	StubFinder stubFinder
	@Autowired
	KafkaTemplate kafkaTemplate
	@Autowired
	EmbeddedKafkaBroker broker
	@Value('${spring.embedded.kafka.brokers}')
	String brokers

	def cleanup() {
		// ensure that message were taken from the queue
		withConsumer({ return null })
	}

	private Message<?> withConsumer(
			Function<Consumer, Message<?>> lambda) {
		Map<String, Object> props = new HashMap<>();
		props.put(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				brokers);
		props.put(
				ConsumerConfig.GROUP_ID_CONFIG,
				"groupId");
		props.put(
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
				JsonDeserializer.class);
		props.put(
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
				JsonDeserializer.class);
		ConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(
				props);
		Consumer<Integer, String> consumer = cf.createConsumer()
		try {
			this.broker.consumeFromAllEmbeddedTopics(consumer);
			return lambda.apply(consumer);
		}
		finally {
			consumer.close()
		}
	}

	private MessageHeaders messageHeaders(Headers headers) {
		Map map = []
		headers.each { map.put(it.key(), it.value()) }
		return new MessageHeaders(map)
	}

	private Message receive(String destination) {
		return withConsumer({ consumer ->
			ConsumerRecord<Integer, String> reply = KafkaTestUtils
					.getSingleRecord(consumer, destination);
			Headers headers = reply.headers();
			String payload = reply.value();
			return MessageBuilder.createMessage(payload, messageHeaders(headers));
		});
	}

	def 'should download the stub and register a route for it'() {
		when:
			// tag::client_send[]
			Message message = MessageBuilder.createMessage(new BookReturned('foo'), new MessageHeaders([sample: "header",]))
			kafkaTemplate.setDefaultTopic('input')
			kafkaTemplate.send(message)
			// end::client_send[]
		then:
			// tag::client_receive[]
			Message receivedMessage = receive('output')
			// end::client_receive[]
		and:
			// tag::client_receive_message[]
			receivedMessage != null
			assertThatBodyContainsBookNameFoo(receivedMessage.getPayload())
			receivedMessage.getHeaders().get('BOOK-NAME') == 'foo'
			// end::client_receive_message[]
	}

	def 'should trigger a message by label'() {
		when:
			// tag::client_trigger[]
			stubFinder.trigger('return_book_1')
			// end::client_trigger[]
		then:
			// tag::client_trigger_receive[]
			Message receivedMessage = receive('output')
			// end::client_trigger_receive[]
		and:
			// tag::client_trigger_message[]
			receivedMessage != null
			assertThatBodyContainsBookNameFoo(receivedMessage.getPayload())
			receivedMessage.getHeaders().get('BOOK-NAME') == 'foo'
			// end::client_trigger_message[]
	}

	def 'should trigger a label for the existing groupId:artifactId'() {
		when:
			// tag::trigger_group_artifact[]
			stubFinder.
					trigger('my:stubs', 'return_book_1')
			// end::trigger_group_artifact[]
		then:
			Message receivedMessage = receive('output')
		and:
			receivedMessage != null
			assertThatBodyContainsBookNameFoo(receivedMessage.getPayload())
			receivedMessage.getHeaders().get('BOOK-NAME') == 'foo'
	}

	def 'should trigger a label for the existing artifactId'() {
		when:
			// tag::trigger_artifact[]
			stubFinder.trigger('stubs', 'return_book_1')
			// end::trigger_artifact[]
		then:
			Message receivedMessage = receive('output')
		and:
			receivedMessage != null
			assertThatBodyContainsBookNameFoo(receivedMessage.getPayload())
			receivedMessage.getHeaders().get('BOOK-NAME') == 'foo'
	}

	def 'should throw an exception when missing label is passed'() {
		when:
			stubFinder.trigger('missing label')
		then:
			thrown(IllegalArgumentException)
	}

	def 'should throw an exception when missing label and artifactid is passed'() {
		when:
			stubFinder.trigger('some:service', 'return_book_1')
		then:
			thrown(IllegalArgumentException)
	}

	def 'should trigger messages by running all triggers'() {
		when:
			// tag::trigger_all[]
			stubFinder.trigger()
			// end::trigger_all[]
		then:
			Message receivedMessage = receive('output')
		and:
			receivedMessage != null
			assertThatBodyContainsBookNameFoo(receivedMessage.getPayload())
			receivedMessage.getHeaders().get('BOOK-NAME') == 'foo'
	}

	def 'should trigger a label with no output message'() {
		when:
			// tag::trigger_no_output[]
			Message message = MessageBuilder.createMessage(new BookReturned('foo'), new MessageHeaders([sample: "header",]))
			kafkaTemplate.setDefaultTopic('delete')
			kafkaTemplate.send(message)
			// end::trigger_no_output[]
		then:
			noExceptionThrown()
	}

	def 'should not trigger a message that does not match input'() {
		when:
			Message message = MessageBuilder.createMessage(new BookReturned('notmatching'), new MessageHeaders([wrong: "header",]))
			kafkaTemplate.setDefaultTopic('input')
			kafkaTemplate.send(message)
		then:
			Message receivedMessage = receive('output')
		and:
			receivedMessage == null
	}

	private boolean assertThatBodyContainsBookNameFoo(Object payload) {
		String objectAsString = payload instanceof String ? payload :
				JsonOutput.toJson(payload)
		def json = new JsonSlurper().parseText(objectAsString)
		return json.bookName == 'foo'
	}

	@Configuration
	@ComponentScan
	@EnableAutoConfiguration
	@EnableKafka
	static class Config {

		@Value('${spring.embedded.kafka.brokers}')
		String bootstrapAddress

		@Bean
		ProducerFactory customProducerFactory() {
			Map<String, Object> configProps = new HashMap<>()
			configProps.put(
					ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
					bootstrapAddress);
			configProps.put(
					ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
					JsonSerializer.class);
			configProps.put(
					ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
					JsonSerializer.class);
			return new DefaultKafkaProducerFactory<>(configProps);
		}

		@Bean
		KafkaTemplate myKafkaTemplate() {
			return new KafkaTemplate<>(customProducerFactory());
		}
	}

	Contract dsl =
			// tag::sample_dsl[]
			Contract.make {
				label 'return_book_1'
				input {
					triggeredBy('bookReturnedTriggered()')
				}
				outputMessage {
					sentTo('output')
					body('''{ "bookName" : "foo" }''')
					headers {
						header('BOOK-NAME', 'foo')
					}
				}
			}
	// end::sample_dsl[]

	Contract dsl2 =
			// tag::sample_dsl_2[]
			Contract.make {
				label 'return_book_2'
				input {
					messageFrom('input')
					messageBody([
							bookName: 'foo'
					])
					messageHeaders {
						header('sample', 'header')
					}
				}
				outputMessage {
					sentTo('output')
					body([
							bookName: 'foo'
					])
					headers {
						header('BOOK-NAME', 'foo')
					}
				}
			}
	// end::sample_dsl_2[]

	Contract dsl3 =
			// tag::sample_dsl_3[]
			Contract.make {
				label 'delete_book'
				input {
					messageFrom('delete')
					messageBody([
							bookName: 'foo'
					])
					messageHeaders {
						header('sample', 'header')
					}
					assertThat('bookWasDeleted()')
				}
			}
	// end::sample_dsl_3[]
}
