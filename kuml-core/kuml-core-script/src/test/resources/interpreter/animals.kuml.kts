classDiagram(name = "Animals") {
    showOperations = false

    val animal = classOf(name = "Animal") {
        isAbstract = true
        attribute(name = "name", type = "String")
        operation(name = "speak") { returns(typeName = "String") }
    }

    val dog = classOf(name = "Dog") {
        attribute(name = "breed", type = "String")
        extends(general = animal)
    }

    val cat = classOf(name = "Cat") {
        extends(general = animal)
    }

    association(source = dog, target = cat) {
        name = "chases"
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "prey" }
    }
}
