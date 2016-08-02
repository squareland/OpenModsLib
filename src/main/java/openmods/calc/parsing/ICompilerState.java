package openmods.calc.parsing;

import java.util.List;

public interface ICompilerState<E> {

	public interface IModifierStateTransition<E> {
		public ICompilerState<E> getState();

		public IExprNode<E> createRootNode(IExprNode<E> child);
	}

	public interface ISymbolStateTransition<E> {
		public ICompilerState<E> getState();

		public IExprNode<E> createRootNode(List<IExprNode<E>> children);
	}

	public IAstParser<E> getParser();

	public ISymbolStateTransition<E> getStateForSymbol(String symbol);

	public IModifierStateTransition<E> getStateForModifier(String modifier);
}
