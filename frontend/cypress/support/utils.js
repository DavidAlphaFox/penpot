export const deleteFirstProject = () => {
    cy.get('.project').first().find("[data-e2e=project-options]").click();
    cy.get('.project').first().find("[data-e2e=Delete]").click();
    cy.get('.accept-button').click();      
 }



 