/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.contract.verifier.builder;

import org.springframework.cloud.contract.spec.Contract;
import org.springframework.cloud.contract.spec.internal.Request;
import org.springframework.cloud.contract.verifier.file.SingleContractMetadata;
import org.springframework.cloud.contract.verifier.http.ContractVerifierHttpMetadata;

class CustomModeSchemeProtocolGiven implements Given {

	private final BlockBuilder blockBuilder;

	CustomModeSchemeProtocolGiven(BlockBuilder blockBuilder) {
		this.blockBuilder = blockBuilder;
	}

	@Override
	public MethodVisitor<Given> apply(SingleContractMetadata metadata) {
		Contract contract = metadata.getContract();
		ContractVerifierHttpMetadata httpMetadata = ContractVerifierHttpMetadata
				.fromMetadata(contract.getMetadata());
		this.blockBuilder
				.addIndented(".scheme(\"" + httpMetadata.getScheme().name() + "\")")
				.addEmptyLine();
		this.blockBuilder.addIndented(
				".protocol(\"" + httpMetadata.getProtocol().toString() + "\")");
		return this;
	}

	@Override
	public boolean accept(SingleContractMetadata metadata) {
		Request request = metadata.getContract().getRequest();
		return request != null;
	}

}
