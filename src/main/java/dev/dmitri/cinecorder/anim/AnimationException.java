package dev.dmitri.cinecorder.anim;

/** Thrown when an animation file cannot be parsed. */
public class AnimationException extends RuntimeException {
	public AnimationException(String message) {
		super(message);
	}
}
