package com.fernando.recruitervisual.vaga;

import java.math.BigDecimal;
import java.util.UUID;

public record EtapaResponse(
		UUID id,
		String name,
		int position,
		boolean proposta,
		long candidatesCount,
		long reprovadosCount,
		long chegaramCount,
		BigDecimal taxaReprovacao) {

	// candidatesCount: candidatos ativos hoje na etapa. reprovadosCount: candidatos reprovados nesta etapa.
	// chegaramCount: candidatos distintos que já chegaram à etapa, mesmo que tenham avançado depois.
	// taxaReprovacao: percentual com uma casa decimal; null quando ninguém chegou à etapa.
	static EtapaResponse from(Etapa etapa, long candidatesCount, long reprovadosCount, long chegaramCount,
			BigDecimal taxaReprovacao) {
		return new EtapaResponse(etapa.getId(), etapa.getName(), etapa.getPosition(), etapa.isProposta(),
				candidatesCount, reprovadosCount, chegaramCount, taxaReprovacao);
	}

}
