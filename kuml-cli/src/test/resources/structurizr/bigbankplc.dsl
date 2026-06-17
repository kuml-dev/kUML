// Big Bank Plc — Structurizr DSL example
// Based on Simon Brown's canonical bigbankplc reference architecture
// (https://structurizr.com/share/36141), adapted as a kUML showcase for
// `kuml import --format structurizr`.
//
// Run:  kuml import --format structurizr bigbankplc.dsl --output bigbankplc.kuml.kts

workspace "Big Bank Plc" "This is an example workspace to illustrate the key features of Structurizr, via the DSL, based around a fictional online banking system." {

    model {
        customer = person "Personal Banking Customer" "A customer of the bank, with personal bank accounts."
        supportStaff = person "Customer Service Staff" "Customer service staff within the bank." {
            tags "Bank Staff"
        }
        backOffice = person "Back Office Staff" "Administration and support staff within the bank." {
            tags "Bank Staff"
        }

        mainframe = softwareSystem "Mainframe Banking System" "Stores all of the core banking information about customers, accounts, transactions, etc." {
            tags "Existing System"
        }

        emailSystem = softwareSystem "E-mail System" "The internal Microsoft Exchange e-mail system." {
            tags "Existing System"
        }

        atm = softwareSystem "ATM" "Allows customers to withdraw cash." {
            tags "Existing System"
        }

        internetBankingSystem = softwareSystem "Internet Banking System" "Allows customers to view information about their bank accounts, and make payments." {
            webApplication = container "Web Application" "Delivers the static content and the Internet banking single page application." "Java and Spring MVC"
            singlePageApplication = container "Single-Page Application" "Provides all of the Internet banking functionality to customers via their web browser." "JavaScript and Angular"
            mobileApp = container "Mobile App" "Provides a limited subset of the Internet banking functionality to customers via their mobile device." "Xamarin"
            apiApplication = container "API Application" "Provides Internet banking functionality via a JSON/HTTPS API." "Java and Spring MVC" {
                signinController = component "Sign In Controller" "Allows users to sign in to the Internet Banking System." "Spring MVC Rest Controller"
                accountsSummaryController = component "Accounts Summary Controller" "Provides customers with a summary of their bank accounts." "Spring MVC Rest Controller"
                resetPasswordController = component "Reset Password Controller" "Allows users to reset their passwords with a single use URL." "Spring MVC Rest Controller"
                securityComponent = component "Security Component" "Provides functionality related to signing in, changing passwords, etc." "Spring Bean"
                mainframeFacade = component "Mainframe Banking System Facade" "A facade onto the mainframe banking system." "Spring Bean"
                emailComponent = component "E-mail Component" "Sends e-mails to users." "Spring Bean"
            }
            database = container "Database" "Stores user registration information, hashed authentication credentials, access logs, etc." "Oracle Database Schema" {
                tags "Database"
            }
        }

        # Relationships — people to systems
        customer -> internetBankingSystem "Views account balances, and makes payments using"
        customer -> atm "Withdraws cash using"
        customer -> supportStaff "Asks questions to"

        supportStaff -> mainframe "Uses"

        backOffice -> mainframe "Uses"

        internetBankingSystem -> mainframe "Gets account information from, and makes payments using"
        internetBankingSystem -> emailSystem "Sends e-mail using"

        emailSystem -> customer "Sends e-mails to"

        atm -> mainframe "Uses"

        # Container relationships
        customer -> webApplication "Visits bigbank.com/ib using" "HTTPS"
        customer -> singlePageApplication "Views account balances, and makes payments using"
        customer -> mobileApp "Views account balances, and makes payments using"

        webApplication -> singlePageApplication "Delivers to the customer's web browser"

        singlePageApplication -> apiApplication "Makes API calls to" "JSON/HTTPS"
        mobileApp -> apiApplication "Makes API calls to" "JSON/HTTPS"

        apiApplication -> database "Reads from and writes to" "JDBC"
        apiApplication -> mainframe "Makes API calls to" "XML/HTTPS"
        apiApplication -> emailSystem "Sends e-mail using"

        # Component relationships
        singlePageApplication -> signinController "Makes API calls to" "JSON/HTTPS"
        singlePageApplication -> accountsSummaryController "Makes API calls to" "JSON/HTTPS"
        singlePageApplication -> resetPasswordController "Makes API calls to" "JSON/HTTPS"
        mobileApp -> signinController "Makes API calls to" "JSON/HTTPS"
        mobileApp -> accountsSummaryController "Makes API calls to" "JSON/HTTPS"
        mobileApp -> resetPasswordController "Makes API calls to" "JSON/HTTPS"

        signinController -> securityComponent "Uses"
        accountsSummaryController -> mainframeFacade "Uses"
        resetPasswordController -> securityComponent "Uses"
        resetPasswordController -> emailComponent "Uses"
        securityComponent -> database "Reads from and writes to" "JDBC"
        mainframeFacade -> mainframe "Makes API calls to" "XML/HTTPS"
        emailComponent -> emailSystem "Sends e-mail using"
    }

    views {
        systemLandscape "SystemLandscape" "The system landscape diagram for Big Bank Plc." {
            include *
            autoLayout lr
        }

        systemContext internetBankingSystem "SystemContext" "An example of a System Context diagram for the Internet Banking System at Big Bank Plc." {
            include *
            autoLayout lr
        }

        container internetBankingSystem "Containers" "The container diagram for the Internet Banking System." {
            include *
            autoLayout lr
        }

        component apiApplication "Components" "The component diagram for the API Application." {
            include *
            autoLayout lr
        }
    }
}
