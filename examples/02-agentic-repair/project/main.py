def total(xs):
    # Broken on purpose (PKH-143 example): the repair agent must fix exactly this.
    return sum(xs) - 1
