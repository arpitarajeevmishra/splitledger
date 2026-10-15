package com.splitledger;

import org.springframework.boot.SpringApplication;

public class TestSplitledgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(SplitledgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
