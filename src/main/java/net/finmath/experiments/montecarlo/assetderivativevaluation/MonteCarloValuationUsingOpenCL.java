/*
 * (c) Copyright Christian P. Fries, Germany. All rights reserved. Contact: email@christianfries.com.
 *
 * Created on 04.03.2021
 */
package net.finmath.experiments.montecarlo.assetderivativevaluation;

import java.util.List;

import net.finmath.exception.CalculationException;
import net.finmath.montecarlo.BrownianMotion;
import net.finmath.montecarlo.BrownianMotionFromMersenneRandomNumbers;
import net.finmath.montecarlo.RandomVariableFactory;
import net.finmath.montecarlo.RandomVariableFromArrayFactory;
import net.finmath.montecarlo.assetderivativevaluation.MonteCarloAssetModel;
import net.finmath.montecarlo.assetderivativevaluation.models.BlackScholesModel;
import net.finmath.montecarlo.assetderivativevaluation.products.EuropeanOption;
import net.finmath.montecarlo.process.EulerSchemeFromProcessModel;
import net.finmath.opencl.montecarlo.RandomVariableOpenCLFactory;
import net.finmath.stochastic.RandomVariable;
import net.finmath.time.TimeDiscretizationFromArray;

public class MonteCarloValuationUsingOpenCL {

	public static void main(String[] args) {

		/**
		 * Factories to test - these are the implementations we inject.
		 * On some machines some tests may fail, e.g. if you do not have a Cuda enabled
		 */
		final var randomVariableFactories = List.of(
				new RandomVariableFromArrayFactory(false),
//				new RandomVariableCudaFactory(),
				new RandomVariableOpenCLFactory()
				);

		for(final RandomVariableFactory randomVariableFactory : randomVariableFactories) {
			try {
				testWithRandomVariableFactory(randomVariableFactory);
			}
			catch(final Exception e) {}
		}

		System.exit(0);
	}

	private static void testWithRandomVariableFactory(RandomVariableFactory randomVariableFactory) {

		System.out.println("Using " + randomVariableFactory.getClass().getSimpleName());

		/*
		 * First run
		 */

		final var run1Start = System.currentTimeMillis();

		// Create Brownian motion
		final int numberOfPaths = 800000;
		final var td = new TimeDiscretizationFromArray(0.0, 200, 0.01);
		final var brownianMotion = new BrownianMotionFromMersenneRandomNumbers(td, 1, numberOfPaths, 3231, randomVariableFactory);

		final var value1 = performValuation(randomVariableFactory, brownianMotion);

		final var run1End = System.currentTimeMillis();
		final var run1Time = (run1End-run1Start)/1000.0;

		System.out.printf("\t (1st run) value = %12.8f \t computation time = %6.3f seconds.\n", value1, run1Time);

		/*
		 * Second run - reusing brownianMotion
		 */
		final var run2Start = System.currentTimeMillis();

		final var value2 = performValuation(randomVariableFactory, brownianMotion);

		final var run2End = System.currentTimeMillis();
		final var run2Time = (run2End-run2Start)/1000.0;

		System.out.printf("\t (2nd run) value = %12.8f \t computation time = %6.3f seconds.\n", value2, run2Time);
	}

	private static double performValuation(RandomVariableFactory randomVariableFactory, BrownianMotion brownianMotion) {
		// Create a model
		final double modelInitialValue = 100.0;
		final double modelRiskFreeRate = 0.05;
		final double modelVolatility = 0.20;
		final var model = new BlackScholesModel(modelInitialValue, modelRiskFreeRate, modelVolatility, randomVariableFactory);

		// Create a corresponding MC process
		final var process = new EulerSchemeFromProcessModel(model, brownianMotion);

		// Using the process (Euler scheme), create an MC simulation of a Black-Scholes model
		final var simulation = new MonteCarloAssetModel(process);

		final double maturity = 2.0;
		final double strike = 106.0;

		final var europeanOption = new EuropeanOption(maturity, strike);

		double value = 0.0;
		try {
			final RandomVariable valueOfEuropeanOption = europeanOption.getValue(0.0, simulation).average();
			value = valueOfEuropeanOption.doubleValue();
		} catch (final CalculationException e) {
			System.out.println("Calculation failed with exception: " + e.getCause().getMessage());
		}

		return value;
	}
}
