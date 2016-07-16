package openmods.calc.types.fp;

import openmods.calc.BinaryFunction;
import openmods.calc.BinaryOperator;
import openmods.calc.Calculator;
import openmods.calc.Constant;
import openmods.calc.GenericFunctions;
import openmods.calc.GenericFunctions.AccumulatorFunction;
import openmods.calc.OperatorDictionary;
import openmods.calc.TopFrame;
import openmods.calc.UnaryFunction;
import openmods.calc.UnaryOperator;
import openmods.calc.parsing.DefaultExprNodeFactory;
import openmods.calc.parsing.IExprNodeFactory;
import openmods.config.simpler.Configurable;

public class DoubleCalculator extends Calculator<Double> {

	private static final double NULL_VALUE = 0.0;

	@Configurable
	public int base = 10;

	@Configurable
	public boolean allowStandardPrinter = false;

	@Configurable
	public boolean uniformBaseNotation = false;

	private final DoublePrinter printer = new DoublePrinter(8);

	public DoubleCalculator(OperatorDictionary<Double> operators, IExprNodeFactory<Double> exprNodeFactory, TopFrame<Double> topFrame) {
		super(new DoubleParser(), NULL_VALUE, operators, exprNodeFactory, topFrame);
	}

	@Override
	public String toString(Double value) {
		if (base == 10 && !allowStandardPrinter && !uniformBaseNotation) {
			return value.toString();
		} else {
			if (value.isNaN()) return "NaN";
			if (value.isInfinite()) return value > 0? "+Inf" : "-Inf";
			final String result = printer.toString(value, base);
			return decorateBase(!uniformBaseNotation, base, result);
		}
	}

	private static final int MAX_PRIO = 5;

	public static DoubleCalculator create() {
		final OperatorDictionary<Double> operators = new OperatorDictionary<Double>();

		operators.registerUnaryOperator(new UnaryOperator<Double>("neg") {
			@Override
			protected Double execute(Double value) {
				return -value;
			}
		});

		operators.registerBinaryOperator(new BinaryOperator<Double>("+", MAX_PRIO - 4) {
			@Override
			protected Double execute(Double left, Double right) {
				return left + right;
			}
		});

		operators.registerUnaryOperator(new UnaryOperator<Double>("+") {
			@Override
			protected Double execute(Double value) {
				return +value;
			}
		});

		operators.registerBinaryOperator(new BinaryOperator<Double>("-", MAX_PRIO - 4) {
			@Override
			protected Double execute(Double left, Double right) {
				return left - right;
			}
		});

		operators.registerUnaryOperator(new UnaryOperator<Double>("-") {
			@Override
			protected Double execute(Double value) {
				return -value;
			}
		});

		operators.registerBinaryOperator(new BinaryOperator<Double>("*", MAX_PRIO - 3) {
			@Override
			protected Double execute(Double left, Double right) {
				return left * right;
			}
		}).setDefault();

		operators.registerBinaryOperator(new BinaryOperator<Double>("/", MAX_PRIO - 3) {
			@Override
			protected Double execute(Double left, Double right) {
				return left / right;
			}
		});

		operators.registerBinaryOperator(new BinaryOperator<Double>("%", MAX_PRIO - 3) {
			@Override
			protected Double execute(Double left, Double right) {
				return left % right;
			}
		});

		operators.registerBinaryOperator(new BinaryOperator<Double>("^", MAX_PRIO - 2) {
			@Override
			protected Double execute(Double left, Double right) {
				return Math.pow(left, right);
			}
		});

		final TopFrame<Double> globals = new TopFrame<Double>();
		GenericFunctions.createStackManipulationFunctions(globals);

		globals.setSymbol("PI", Constant.create(Math.PI));
		globals.setSymbol("E", Constant.create(Math.E));
		globals.setSymbol("INF", Constant.create(Double.POSITIVE_INFINITY));
		globals.setSymbol("MAX", Constant.create(Double.MIN_VALUE));

		globals.setSymbol("abs", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.abs(value);
			}
		});

		globals.setSymbol("sgn", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.signum(value);
			}
		});

		globals.setSymbol("sqrt", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.sqrt(value);
			}
		});

		globals.setSymbol("ceil", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.ceil(value);
			}
		});

		globals.setSymbol("floor", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.floor(value);
			}
		});

		globals.setSymbol("cos", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.cos(value);
			}
		});

		globals.setSymbol("sin", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.sin(value);
			}
		});

		globals.setSymbol("tan", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.tan(value);
			}
		});

		globals.setSymbol("acos", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.acos(value);
			}
		});

		globals.setSymbol("asin", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.asin(value);
			}
		});

		globals.setSymbol("atan", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.atan(value);
			}
		});

		globals.setSymbol("atan2", new BinaryFunction<Double>() {
			@Override
			protected Double execute(Double left, Double right) {
				return Math.atan2(left, right);
			}

		});

		globals.setSymbol("log10", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.log10(value);
			}
		});

		globals.setSymbol("ln", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.log(value);
			}
		});

		globals.setSymbol("log", new BinaryFunction<Double>() {
			@Override
			protected Double execute(Double left, Double right) {
				return Math.log(left) / Math.log(right);
			}
		});

		globals.setSymbol("exp", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.exp(value);
			}
		});

		globals.setSymbol("min", new AccumulatorFunction<Double>(NULL_VALUE) {
			@Override
			protected Double accumulate(Double result, Double value) {
				return Math.min(result, value);
			}
		});

		globals.setSymbol("max", new AccumulatorFunction<Double>(NULL_VALUE) {
			@Override
			protected Double accumulate(Double result, Double value) {
				return Math.max(result, value);
			}
		});

		globals.setSymbol("sum", new AccumulatorFunction<Double>(NULL_VALUE) {
			@Override
			protected Double accumulate(Double result, Double value) {
				return result + value;
			}
		});

		globals.setSymbol("avg", new AccumulatorFunction<Double>(NULL_VALUE) {
			@Override
			protected Double accumulate(Double result, Double value) {
				return result + value;
			}

			@Override
			protected Double process(Double result, int argCount) {
				return result / argCount;
			}
		});

		globals.setSymbol("rad", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.toRadians(value);
			}
		});

		globals.setSymbol("deg", new UnaryFunction<Double>() {
			@Override
			protected Double execute(Double value) {
				return Math.toDegrees(value);
			}
		});

		final IExprNodeFactory<Double> exprNodeFactory = new DefaultExprNodeFactory<Double>();
		return new DoubleCalculator(operators, exprNodeFactory, globals);
	}

}
