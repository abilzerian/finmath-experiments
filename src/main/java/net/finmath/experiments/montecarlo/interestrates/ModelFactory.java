package net.finmath.experiments.montecarlo.interestrates;

import java.util.List;
import java.util.Map;

import net.finmath.exception.CalculationException;
import net.finmath.marketdata.model.AnalyticModel;
import net.finmath.marketdata.model.AnalyticModelFromCurvesAndVols;
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.DiscountCurveFromForwardCurve;
import net.finmath.marketdata.model.curves.ForwardCurve;
import net.finmath.marketdata.model.curves.ForwardCurveInterpolation;
import net.finmath.montecarlo.BrownianMotion;
import net.finmath.montecarlo.BrownianMotionFromMersenneRandomNumbers;
import net.finmath.montecarlo.RandomVariableFactory;
import net.finmath.montecarlo.interestrate.CalibrationProduct;
import net.finmath.montecarlo.interestrate.LIBORMonteCarloSimulationFromLIBORModel;
import net.finmath.montecarlo.interestrate.TermStructureMonteCarloSimulationModel;
import net.finmath.montecarlo.interestrate.models.LIBORMarketModelFromCovarianceModel;
import net.finmath.montecarlo.interestrate.models.covariance.BlendedLocalVolatilityModel;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORCorrelationModel;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORCorrelationModelExponentialDecay;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORCovarianceModel;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORCovarianceModelFromVolatilityAndCorrelation;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORVolatilityModel;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORVolatilityModelFourParameterExponentialForm;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORVolatilityModelFromGivenMatrix;
import net.finmath.montecarlo.process.EulerSchemeFromProcessModel;
import net.finmath.montecarlo.process.MonteCarloProcess;
import net.finmath.time.TimeDiscretization;
import net.finmath.time.TimeDiscretizationFromArray;

public class ModelFactory {

	public static TermStructureMonteCarloSimulationModel createTermStuctureModel(
			RandomVariableFactory randomVariableFactory,
			String measure,
			String simulationTimeInterpolationMethod,
			double forwardRate,
			double periodLength,
			double timeHorizon,
			boolean useDiscountCurve,
			double volatility,
			double volatilityExponentialDecay,
			double localVolNormalityBlend,
			double correlationDecayParam,
			double simulationTimeStep,
			int numberOfFactors,
			int numberOfPaths,
			int seed
			) throws CalculationException {

		/*
		 * Create the forward rate tenor structure and the initial values (the T_i)
		 */
		final double forwardRatePeriodLength 		= periodLength;
		final double forwardRateRateTimeHorzion		= timeHorizon;
		final int numberOfPeriods = (int) (forwardRateRateTimeHorzion / forwardRatePeriodLength);
		final TimeDiscretization liborPeriodDiscretization = new TimeDiscretizationFromArray(0.0, numberOfPeriods, forwardRatePeriodLength);

		/*
		 * Interest rate curve (here a flat (constant) value of forwardRate for the curve of forward rates
		 */
	
		// Create the forward curve (initial value of the term structure model) - this curve is flat
		final ForwardCurve forwardCurve = ForwardCurveInterpolation.createForwardCurveFromForwards(
				"forwardCurve"								/* name of the curve */,
				new double[] {       0.5 ,        1.0 ,        2.0 ,        5.0 ,        40.0}	/* fixings of the forward */,
				new double[] {forwardRate, forwardRate, forwardRate, forwardRate, forwardRate}	/* forwards */,
				forwardRatePeriodLength							/* tenor / period length */
				);

		// Discount curve used for numeraire adjustment (if requested)
		final DiscountCurve discountCurve = useDiscountCurve ? new DiscountCurveFromForwardCurve(forwardCurve) : null;

		final AnalyticModel curveModel = new AnalyticModelFromCurvesAndVols(useDiscountCurve ? List.of(forwardCurve, discountCurve) : List.of(forwardCurve));
		
		/*
		 * Create a simulation time discretization (the t_j)
		 */
		final double lastTime	= 20.0;
		final double dt			= simulationTimeStep;
		final TimeDiscretization timeDiscretization = new TimeDiscretizationFromArray(0.0, (int) (lastTime / dt), dt);

		/*
		 * Create a volatility model \sigma_{i}(t_{j}) = a * exp(-c (T_{i}-t_{j}))
		 */
		final LIBORVolatilityModel volatilityModel = new LIBORVolatilityModelFourParameterExponentialForm(
				timeDiscretization, liborPeriodDiscretization, volatility, 0, volatilityExponentialDecay, 0.0, false);
		
		/*
		 * Create a correlation model \rho_{i,j} = exp(-a * abs(T_i-T_j))
		 */
		final LIBORCorrelationModel correlationModel = new LIBORCorrelationModelExponentialDecay(
				timeDiscretization, liborPeriodDiscretization, numberOfFactors, correlationDecayParam);

		/*
		 * Combine volatility model and correlation model to a covariance model
		 */
		final LIBORCovarianceModelFromVolatilityAndCorrelation covarianceModelWithConstantVolatility = new LIBORCovarianceModelFromVolatilityAndCorrelation(
				timeDiscretization, liborPeriodDiscretization, volatilityModel, correlationModel);

		/*
		 * BlendedLocalVolatlityModel: Puts the factor (\alpha L_{i}(0) + (1-\alpha) L_{i}(t)) in front of the diffusion.
		 */
		final LIBORCovarianceModel covarianceModelBlended = new BlendedLocalVolatilityModel(
				covarianceModelWithConstantVolatility, forwardCurve, localVolNormalityBlend, false);

		/*
		 * Set model properties
		 */
		final Map<String, String> properties = Map.of(
				"measure", measure,																		// Choose the simulation measure
				"stateSpace", LIBORMarketModelFromCovarianceModel.StateSpace.NORMAL.name(),				// Choose state space
				"interpolationMethod", "linear",														// Interpolation of the tenor
				"simulationTimeInterpolationMethod", simulationTimeInterpolationMethod					// Interpolation of the simulation time
				);

		// Empty array of calibration items - hence, model will use given covariance
		final CalibrationProduct[] calibrationItems = new CalibrationProduct[0];

		/*
		 * Create corresponding ProcessModel specification of the discrete term structure model
		 */
		final LIBORMarketModelFromCovarianceModel liborMarketModel = new LIBORMarketModelFromCovarianceModel(
				liborPeriodDiscretization, curveModel, forwardCurve, discountCurve, randomVariableFactory,
				covarianceModelBlended, calibrationItems, properties);

		final BrownianMotion brownianMotion = new BrownianMotionFromMersenneRandomNumbers(timeDiscretization, numberOfFactors, numberOfPaths, seed);

		final MonteCarloProcess process = new EulerSchemeFromProcessModel(liborMarketModel, brownianMotion);

		return new LIBORMonteCarloSimulationFromLIBORModel(process);
	}

	public static TermStructureMonteCarloSimulationModel createTermStuctureModel(
			RandomVariableFactory randomVariableFactory,
			String measure,
			String simulationTimeInterpolationMethod,
			double forwardRate,
			double periodLength,
			double timeHorizon,
			boolean useDiscountCurve,
			double[][] volatilityMatrix,
			double localVolNormalityBlend,
			double correlationDecayParam,
			double simulationTimeStep,
			int numberOfFactors,
			int numberOfPaths,
			int seed
			) throws CalculationException {

		/*
		 * Create the forward rate tenor structure and the initial values (the T_i)
		 */
		final double forwardRatePeriodLength 		= periodLength;
		final double forwardRateRateTimeHorzion		= timeHorizon;
		final int numberOfPeriods = (int) (forwardRateRateTimeHorzion / forwardRatePeriodLength);
		final TimeDiscretization liborPeriodDiscretization = new TimeDiscretizationFromArray(0.0, numberOfPeriods, forwardRatePeriodLength);

		/*
		 * Interest rate curve (here a flat (constant) value of forwardRate for the curve of forward rates
		 */
	
		// Create the forward curve (initial value of the term structure model) - this curve is flat
		final ForwardCurve forwardCurve = ForwardCurveInterpolation.createForwardCurveFromForwards(
				"forwardCurve"								/* name of the curve */,
				new double[] {       0.5 ,        1.0 ,        2.0 ,        5.0 ,        40.0}	/* fixings of the forward */,
				new double[] {forwardRate, forwardRate, forwardRate, forwardRate, forwardRate}	/* forwards */,
				forwardRatePeriodLength							/* tenor / period length */
				);

		// Discount curve used for numeraire adjustment (if requested)
		final DiscountCurve discountCurve = useDiscountCurve ? new DiscountCurveFromForwardCurve(forwardCurve) : null;

		final AnalyticModel curveModel = new AnalyticModelFromCurvesAndVols(useDiscountCurve ? List.of(forwardCurve, discountCurve) : List.of(forwardCurve));
		
		/*
		 * Create a simulation time discretization (the t_j)
		 */
		final double lastTime	= 20.0;
		final double dt			= simulationTimeStep;
		final TimeDiscretization timeDiscretization = new TimeDiscretizationFromArray(0.0, (int) (lastTime / dt), dt);

		
		/*
		 * Create a volatility model \sigma_{i,j} from a given matrix
		 */
		LIBORVolatilityModelFromGivenMatrix volatilityModel = new LIBORVolatilityModelFromGivenMatrix(
				randomVariableFactory,
				timeDiscretization,
				liborPeriodDiscretization,
				volatilityMatrix,
				false);

		/*
		 * Create a correlation model \rho_{i,j} = exp(-a * abs(T_i-T_j))
		 */
		final LIBORCorrelationModel correlationModel = new LIBORCorrelationModelExponentialDecay(
				timeDiscretization, liborPeriodDiscretization, numberOfFactors, correlationDecayParam);

		/*
		 * Combine volatility model and correlation model to a covariance model
		 */
		final LIBORCovarianceModelFromVolatilityAndCorrelation covarianceModelWithConstantVolatility = new LIBORCovarianceModelFromVolatilityAndCorrelation(
				timeDiscretization, liborPeriodDiscretization, volatilityModel, correlationModel);

		/*
		 * BlendedLocalVolatlityModel: Puts the factor (\alpha L_{i}(0) + (1-\alpha) L_{i}(t)) in front of the diffusion.
		 */
		final LIBORCovarianceModel covarianceModelBlended = new BlendedLocalVolatilityModel(
				covarianceModelWithConstantVolatility, forwardCurve, localVolNormalityBlend, false);

		/*
		 * Set model properties
		 */
		final Map<String, String> properties = Map.of(
				"measure", measure,																		// Choose the simulation measure
				"stateSpace", LIBORMarketModelFromCovarianceModel.StateSpace.NORMAL.name(),				// Choose state space
				"interpolationMethod", "linear",														// Interpolation of the tenor
				"simulationTimeInterpolationMethod", simulationTimeInterpolationMethod					// Interpolation of the simulation time
				);

		// Empty array of calibration items - hence, model will use given covariance
		final CalibrationProduct[] calibrationItems = new CalibrationProduct[0];

		/*
		 * Create corresponding ProcessModel specification of the discrete term structure model
		 */
		final LIBORMarketModelFromCovarianceModel liborMarketModel = new LIBORMarketModelFromCovarianceModel(
				liborPeriodDiscretization, curveModel, forwardCurve, discountCurve, randomVariableFactory,
				covarianceModelBlended, calibrationItems, properties);

		final BrownianMotion brownianMotion = new BrownianMotionFromMersenneRandomNumbers(timeDiscretization, numberOfFactors, numberOfPaths, seed);

		final MonteCarloProcess process = new EulerSchemeFromProcessModel(liborMarketModel, brownianMotion);

		return new LIBORMonteCarloSimulationFromLIBORModel(process);
	}

	/*
	 * Some version with default arguments
	 */
	public static TermStructureMonteCarloSimulationModel createTermStuctureModel(
			RandomVariableFactory randomVariableFactory,
			String measure,
			double forwardRate,
			double periodLength,
			double timeHorizon,
			boolean useDiscountCurve,
			double volatility,
			double volatilityExponentialDecay,
			double localVolNormalityBlend,
			double correlationDecayParam,
			double simulationTimeStep,
			int numberOfFactors,
			int numberOfPaths,
			int seed
			) throws CalculationException {

		return createTermStuctureModel(randomVariableFactory, measure, "round_down" /* simulationTimeInterpolationMethod */, forwardRate, periodLength, timeHorizon, useDiscountCurve, volatility, volatilityExponentialDecay, localVolNormalityBlend, correlationDecayParam, simulationTimeStep, numberOfFactors, numberOfPaths, seed);
	}

	public static TermStructureMonteCarloSimulationModel createTermStuctureModel(
			RandomVariableFactory randomVariableFactory,
			String measure,
			String simulationTimeInterpolationMethod,
			double forwardRate,
			double periodLength,
			double timeHorizon,
			boolean useDiscountCurve,
			double volatility,
			double localVolNormalityBlend,
			double correlationDecayParam,
			double simulationTimeStep,
			int numberOfFactors,
			int numberOfPaths,
			int seed
			) throws CalculationException {

		return createTermStuctureModel(randomVariableFactory, measure, simulationTimeInterpolationMethod, forwardRate, periodLength, timeHorizon, useDiscountCurve, volatility, 0.0 /* volatilityExponentialDecay */, localVolNormalityBlend, correlationDecayParam, simulationTimeStep, numberOfFactors, numberOfPaths, seed);
	}

	public static TermStructureMonteCarloSimulationModel createTermStuctureModel(
			RandomVariableFactory randomVariableFactory,
			String measure,
			double forwardRate,
			double periodLength,
			double timeHorizon,
			boolean useDiscountCurve,
			double volatility,
			double localVolNormalityBlend,
			double correlationDecayParam,
			double simulationTimeStep,
			int numberOfFactors,
			int numberOfPaths,
			int seed
			) throws CalculationException {

		return createTermStuctureModel(randomVariableFactory, measure, "round_down" /* simulationTimeInterpolationMethod */, forwardRate, periodLength, timeHorizon, useDiscountCurve, volatility,  0.0 /* volatilityExponentialDecay */, localVolNormalityBlend, correlationDecayParam, simulationTimeStep, numberOfFactors, numberOfPaths, seed);
	}
}
